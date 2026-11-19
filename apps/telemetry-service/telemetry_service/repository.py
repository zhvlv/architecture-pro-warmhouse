from __future__ import annotations

from abc import ABC, abstractmethod
from contextlib import contextmanager
from typing import Iterator

from .errors import DependencyUnavailable
from .models import Reading


class ReadingRepository(ABC):
    @abstractmethod
    def add(self, reading: Reading) -> Reading:
        raise NotImplementedError

    @abstractmethod
    def latest(self, device_id: int) -> Reading | None:
        raise NotImplementedError

    @abstractmethod
    def history(self, device_id: int, limit: int) -> list[Reading]:
        raise NotImplementedError

    @abstractmethod
    def ping(self) -> None:
        raise NotImplementedError


class PostgresReadingRepository(ReadingRepository):
    COLUMNS = "id, device_id, value, unit, status, observed_at, source"

    def __init__(self, database_url: str, connect_timeout: int = 3):
        import psycopg
        from psycopg.rows import dict_row

        self._psycopg = psycopg
        self._dict_row = dict_row
        self.database_url = database_url
        self.connect_timeout = connect_timeout

    @contextmanager
    def _connection(self) -> Iterator:
        try:
            # A connection per request avoids sharing transaction state across HTTP threads.
            with self._psycopg.connect(
                self.database_url,
                row_factory=self._dict_row,
                connect_timeout=self.connect_timeout,
                options="-c statement_timeout=5000",
            ) as connection:
                yield connection
        except self._psycopg.Error as exc:
            raise DependencyUnavailable("Telemetry database unavailable") from exc

    @staticmethod
    def _reading(row: dict) -> Reading:
        return Reading(
            id=row["id"],
            device_id=row["device_id"],
            value=row["value"],
            unit=row["unit"],
            status=row["status"],
            timestamp=row["observed_at"],
            source=row["source"],
        )

    def add(self, reading: Reading) -> Reading:
        with self._connection() as connection:
            row = connection.execute(
                f"INSERT INTO readings (device_id, value, unit, status, observed_at, source) "
                f"VALUES (%s, %s, %s, %s, %s, %s) RETURNING {self.COLUMNS}",
                (
                    reading.device_id,
                    reading.value,
                    reading.unit,
                    reading.status,
                    reading.timestamp,
                    reading.source,
                ),
            ).fetchone()

            return self._reading(row)

    def latest(self, device_id: int) -> Reading | None:
        with self._connection() as connection:
            row = connection.execute(
                f"SELECT {self.COLUMNS} FROM readings WHERE device_id = %s "
                "ORDER BY observed_at DESC, id DESC LIMIT 1",
                (device_id,),
            ).fetchone()

            return self._reading(row) if row else None

    def history(self, device_id: int, limit: int) -> list[Reading]:
        with self._connection() as connection:
            rows = connection.execute(
                f"SELECT {self.COLUMNS} FROM readings WHERE device_id = %s "
                "ORDER BY observed_at DESC, id DESC LIMIT %s",
                (device_id, limit),
            ).fetchall()

            return [self._reading(row) for row in rows]

    def ping(self) -> None:
        with self._connection() as connection:
            connection.execute("SELECT 1").fetchone()
