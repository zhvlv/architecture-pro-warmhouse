from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import math
import re
from typing import Any

from .errors import ValidationError


STATUSES = frozenset({"active", "inactive", "error"})


def positive_id(value: Any, name: str = "device_id") -> int:
    if (
        isinstance(value, bool)
        or not isinstance(value, int)
        or not 1 <= value <= 9223372036854775807
    ):
        raise ValidationError(f"{name} must be a positive integer")

    return value


def finite_value(value: Any) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValidationError("value must be a finite number")

    try:
        result = float(value)
    except (ValueError, OverflowError) as exc:
        raise ValidationError("value must be a finite number") from exc

    if not math.isfinite(result):
        raise ValidationError("value must be a finite number")

    return result


def parse_timestamp(value: Any) -> datetime:
    if not isinstance(value, str) or not re.fullmatch(
        r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})",
        value,
    ):
        raise ValidationError("timestamp must be an RFC3339 date with timezone")

    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValidationError("timestamp must be an RFC3339 date with timezone") from exc

    if parsed.tzinfo is None:
        raise ValidationError("timestamp must include a timezone")

    return parsed.astimezone(timezone.utc)


def utc_timestamp(value: datetime) -> str:
    return (
        value.astimezone(timezone.utc)
        .isoformat(timespec="microseconds")
        .replace("+00:00", "Z")
    )


@dataclass(frozen=True)
class Device:
    id: int
    type: str
    location: str
    unit: str


@dataclass(frozen=True)
class Reading:
    id: int | None
    device_id: int
    value: float
    unit: str
    status: str
    timestamp: datetime
    source: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "device_id": self.device_id,
            "value": self.value,
            "unit": self.unit,
            "status": self.status,
            "timestamp": utc_timestamp(self.timestamp),
            "source": self.source,
        }
