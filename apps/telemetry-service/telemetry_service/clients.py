from __future__ import annotations

from abc import ABC, abstractmethod
import json
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from .errors import DependencyUnavailable, NotFound, ValidationError
from .models import Device, Reading, STATUSES, finite_value, parse_timestamp


class DeviceClient(ABC):
    @abstractmethod
    def get(self, device_id: int) -> Device:
        raise NotImplementedError


class TemperatureClient(ABC):
    @abstractmethod
    def collect(self, device: Device) -> Reading:
        raise NotImplementedError


class JsonHttpClient:
    def __init__(self, base_url: str, service_name: str, timeout: float = 3):
        self.base_url = base_url.rstrip("/")
        self.service_name = service_name
        self.timeout = timeout

    def get_json(self, path: str) -> dict:
        request = Request(
            self.base_url + path,
            headers={"Accept": "application/json"},
        )

        try:
            with urlopen(request, timeout=self.timeout) as response:
                if response.status != 200:
                    raise DependencyUnavailable(
                        f"{self.service_name} unavailable"
                    )

                content = response.read()
                result = json.loads(content)
                if not isinstance(result, dict):
                    raise ValueError("Expected a JSON object")

                return result
        except HTTPError as exc:
            if exc.code == 404:
                raise NotFound("Device not found") from exc

            raise DependencyUnavailable(
                f"{self.service_name} unavailable"
            ) from exc
        except (URLError, TimeoutError, OSError) as exc:
            raise DependencyUnavailable(
                f"{self.service_name} unavailable"
            ) from exc
        except (ValueError, UnicodeError) as exc:
            raise DependencyUnavailable(
                f"Invalid response from {self.service_name}"
            ) from exc


class HttpDeviceClient(DeviceClient):
    def __init__(self, base_url: str, timeout: float = 3):
        self.http = JsonHttpClient(base_url, "Device service", timeout)

    def get(self, device_id: int) -> Device:
        payload = self.http.get_json(f"/api/v1/devices/{device_id}")
        if (
            isinstance(payload.get("id"), bool)
            or payload.get("id") != device_id
            or not isinstance(payload.get("type"), str)
            or not isinstance(payload.get("location"), str)
            or not isinstance(payload.get("unit", ""), str)
        ):
            raise DependencyUnavailable("Invalid response from Device service")

        return Device(
            device_id,
            payload["type"],
            payload["location"],
            payload.get("unit", ""),
        )


class HttpTemperatureClient(TemperatureClient):
    def __init__(self, base_url: str, timeout: float = 3):
        self.http = JsonHttpClient(base_url, "Temperature service", timeout)

    def collect(self, device: Device) -> Reading:
        query = urlencode({
            "location": device.location,
            "sensor_id": str(device.id),
        })

        try:
            payload = self.http.get_json(f"/temperature?{query}")
            if str(payload.get("sensor_id")) != str(device.id):
                raise ValueError("Unexpected sensor id")

            status = payload.get("status")
            unit = payload.get("unit")
            if (
                status not in STATUSES
                or not isinstance(unit, str)
                or len(unit) > 32
            ):
                raise ValueError("Invalid status or unit")

            return Reading(
                id=None,
                device_id=device.id,
                value=finite_value(payload.get("value")),
                unit=unit,
                status=status,
                timestamp=parse_timestamp(payload.get("timestamp")),
                source="simulator",
            )
        except (ValidationError, ValueError, TypeError) as exc:
            raise DependencyUnavailable(
                "Invalid response from Temperature service"
            ) from exc
        except NotFound as exc:
            raise DependencyUnavailable(
                "Temperature service unavailable"
            ) from exc
