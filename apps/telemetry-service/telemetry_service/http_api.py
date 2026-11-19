from __future__ import annotations

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import logging
import re
from urllib.parse import parse_qs, urlsplit

from .errors import ApiError, ValidationError
from .service import TelemetryService


logger = logging.getLogger(__name__)


class TelemetryHttpServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], service: TelemetryService):
        self.service = service
        super().__init__(address, TelemetryRequestHandler)


class TelemetryRequestHandler(BaseHTTPRequestHandler):
    LATEST_PATH = re.compile(r"/api/v1/devices/([^/]+)/readings/latest")
    HISTORY_PATH = re.compile(r"/api/v1/devices/([^/]+)/readings")

    def setup(self):
        super().setup()
        self.connection.settimeout(10)

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")

    def do_PUT(self):
        self._handle("PUT")

    def do_PATCH(self):
        self._handle("PATCH")

    def do_DELETE(self):
        self._handle("DELETE")

    def do_OPTIONS(self):
        self._handle("OPTIONS")

    @staticmethod
    def _integer(value: str, name: str) -> int:
        if not value.isascii() or not value.isdecimal():
            raise ValidationError(f"{name} must be a positive integer")

        try:
            result = int(value)
        except ValueError as exc:
            raise ValidationError(
                f"{name} must be a positive integer"
            ) from exc

        if result < 1 or result > 9223372036854775807:
            raise ValidationError(f"{name} must be a positive integer")

        return result

    @staticmethod
    def _query(raw_query: str, allowed: set[str]) -> dict[str, str]:
        parsed = parse_qs(raw_query, keep_blank_values=True)
        if parsed.keys() - allowed or any(
            len(values) != 1 for values in parsed.values()
        ):
            raise ValidationError("Invalid query parameters")

        return {key: values[0] for key, values in parsed.items()}

    def _handle(self, method: str):
        try:
            url = urlsplit(self.path)
            path = url.path.rstrip("/") or "/"
            latest = self.LATEST_PATH.fullmatch(path)
            history = self.HISTORY_PATH.fullmatch(path)

            if path == "/health":
                if method != "GET":
                    self._json(
                        405,
                        {"error": "Method not allowed"},
                        allow="GET",
                    )
                else:
                    self._json(200, self.server.service.health())
            elif path == "/api/v1/readings":
                if method != "POST":
                    self._json(
                        405,
                        {"error": "Method not allowed"},
                        allow="POST",
                    )
                else:
                    self._query(url.query, set())
                    reading = self.server.service.record(self._body())
                    self._json(201, reading.to_dict())
            elif latest:
                if method != "GET":
                    self._json(
                        405,
                        {"error": "Method not allowed"},
                        allow="GET",
                    )
                else:
                    device_id = self._integer(latest[1], "device_id")
                    query = self._query(url.query, {"refresh"})
                    refresh = query.get("refresh", "true")
                    if refresh not in {"true", "false"}:
                        raise ValidationError("refresh must be true or false")

                    reading = self.server.service.latest(
                        device_id,
                        refresh == "true",
                    )
                    self._json(200, reading.to_dict())
            elif history:
                if method != "GET":
                    self._json(
                        405,
                        {"error": "Method not allowed"},
                        allow="GET",
                    )
                else:
                    device_id = self._integer(history[1], "device_id")
                    query = self._query(url.query, {"limit"})
                    limit = self._integer(query.get("limit", "100"), "limit")
                    readings = self.server.service.history(device_id, limit)
                    self._json(
                        200,
                        [reading.to_dict() for reading in readings],
                    )
            else:
                self._json(404, {"error": "Endpoint not found"})
        except ApiError as exc:
            self._json(exc.status_code, {"error": exc.message})
        except Exception:
            logger.exception("Unhandled telemetry request failure")
            self._json(500, {"error": "Internal server error"})

    def _body(self):
        content_type = (
            self.headers.get("Content-Type", "")
            .split(";", 1)[0]
            .strip()
            .lower()
        )
        if content_type != "application/json":
            raise ApiError(415, "Content-Type must be application/json")

        if self.headers.get("Transfer-Encoding"):
            raise ValidationError("Transfer-Encoding is not supported")

        length = self.headers.get("Content-Length", "")
        if not length.isascii() or not length.isdecimal() or len(length) > 10:
            raise ValidationError("Content-Length is required")

        size = int(length)
        if size == 0:
            raise ValidationError("Request body is required")

        try:
            content = self.rfile.read(size)
            if len(content) != size:
                raise ValueError("Incomplete request body")

            return json.loads(content, parse_constant=self._invalid_constant)
        except (ValueError, UnicodeError) as exc:
            raise ValidationError("Invalid JSON body") from exc

    @staticmethod
    def _invalid_constant(_value):
        raise ValueError("Non-finite JSON number")

    def _json(self, status: int, payload, allow: str | None = None):
        body = json.dumps(
            payload,
            ensure_ascii=False,
            allow_nan=False,
        ).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        if allow:
            self.send_header("Allow", allow)

        self.end_headers()
        self.wfile.write(body)

    def log_message(self, message, *args):
        logger.info(message, *args)
