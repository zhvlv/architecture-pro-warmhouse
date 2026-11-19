from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Callable

from .clients import DeviceClient, TemperatureClient
from .errors import NotFound, ValidationError
from .models import Reading, STATUSES, finite_value, parse_timestamp, positive_id
from .repository import ReadingRepository


class TelemetryService:
    def __init__(
        self,
        repository: ReadingRepository,
        devices: DeviceClient,
        temperatures: TemperatureClient,
        clock: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
    ):
        self.repository = repository
        self.devices = devices
        self.temperatures = temperatures
        self.clock = clock

    def record(self, payload: Any) -> Reading:
        if not isinstance(payload, dict):
            raise ValidationError("Expected a JSON object")

        unknown = payload.keys() - {"device_id", "value", "unit", "status", "timestamp"}
        if unknown:
            raise ValidationError("Unknown fields: " + ", ".join(sorted(unknown)))

        device_id = positive_id(payload.get("device_id"))
        value = finite_value(payload.get("value"))
        status = payload.get("status", "active")
        if not isinstance(status, str) or status not in STATUSES:
            raise ValidationError("status must be active, inactive or error")

        timestamp = (
            parse_timestamp(payload["timestamp"])
            if "timestamp" in payload
            else self.clock()
        )
        if "unit" in payload and (
            not isinstance(payload["unit"], str) or len(payload["unit"]) > 32
        ):
            raise ValidationError("unit must be a string of at most 32 characters")

        device = self.devices.get(device_id)
        reading = Reading(
            id=None,
            device_id=device_id,
            value=value,
            unit=payload.get("unit", device.unit),
            status=status,
            timestamp=timestamp,
            source="manual",
        )

        return self.repository.add(reading)

    def latest(self, device_id: int, refresh: bool = True) -> Reading:
        positive_id(device_id)
        device = self.devices.get(device_id)

        if refresh and device.type == "temperature":
            return self.repository.add(self.temperatures.collect(device))

        reading = self.repository.latest(device_id)
        if reading is None:
            raise NotFound("No readings for this device")

        return reading

    def history(self, device_id: int, limit: int = 100) -> list[Reading]:
        positive_id(device_id)
        if (
            isinstance(limit, bool)
            or not isinstance(limit, int)
            or not 1 <= limit <= 1000
        ):
            raise ValidationError("limit must be an integer from 1 to 1000")

        self.devices.get(device_id)

        return self.repository.history(device_id, limit)

    def health(self) -> dict[str, str]:
        self.repository.ping()

        return {"status": "ok"}
