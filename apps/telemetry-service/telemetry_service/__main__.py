from dataclasses import dataclass
import logging
import math
import os
import signal
import threading

from .clients import HttpDeviceClient, HttpTemperatureClient
from .http_api import TelemetryHttpServer
from .repository import PostgresReadingRepository
from .service import TelemetryService


@dataclass(frozen=True)
class Settings:
    database_url: str
    device_api_url: str
    temperature_api_url: str
    port: int
    http_timeout: float

    @classmethod
    def from_env(cls):
        database_url = os.environ.get("DATABASE_URL", "")
        if not database_url:
            raise ValueError("DATABASE_URL is required")

        port = int(os.environ.get("PORT", "8083"))
        timeout = float(os.environ.get("HTTP_TIMEOUT_SECONDS", "3"))
        if not 1 <= port <= 65535:
            raise ValueError("PORT must be from 1 to 65535")
        if not math.isfinite(timeout) or timeout <= 0:
            raise ValueError("HTTP_TIMEOUT_SECONDS must be a positive finite number")

        return cls(
            database_url=database_url,
            device_api_url=os.environ.get(
                "DEVICE_API_URL", "http://device-service:8082"
            ),
            temperature_api_url=os.environ.get(
                "TEMPERATURE_API_URL", "http://temperature-api:8081"
            ),
            port=port,
            http_timeout=timeout,
        )


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )

    settings = Settings.from_env()
    repository = PostgresReadingRepository(settings.database_url)
    service = TelemetryService(
        repository,
        HttpDeviceClient(settings.device_api_url, settings.http_timeout),
        HttpTemperatureClient(settings.temperature_api_url, settings.http_timeout),
    )
    server = TelemetryHttpServer(("0.0.0.0", settings.port), service)

    def stop(_signum, _frame):
        # shutdown must run outside serve_forever's own thread.
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    logging.info("Telemetry API listening on port %s", settings.port)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
