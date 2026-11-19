package com.warmhouse.devices;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public final class ApiServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final DeviceService service;

    public ApiServer(InetSocketAddress address, DeviceService service) throws IOException {
        this.service = service;
        server = HttpServer.create(address, 64);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (ApiException exception) {
            respond(exchange, exception.status(), Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            System.err.println("Unhandled device API error: " + exception.getClass().getSimpleName());
            respond(exchange, 500, Map.of("error", "Internal server error"));
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        if (path.equals("/health")) {
            requireMethod(method, "GET");
            if (!service.healthy()) {
                throw new ApiException(503, "Device database is unavailable");
            }

            respond(exchange, 200, Map.of("status", "ok"));
            return;
        }

        if (path.equals("/api/v1/devices")) {
            switch (method) {
                case "GET" -> respond(
                        exchange,
                        200,
                        service.list(query(exchange, "type")).stream()
                                .map(Device::json)
                                .toList());
                case "POST" -> {
                    var device = service.create(body(exchange));
                    exchange.getResponseHeaders().set("Location", "/api/v1/devices/" + device.id());
                    respond(exchange, 201, device.json());
                }
                default -> throw new ApiException(405, "Method not allowed");
            }

            return;
        }

        if (path.startsWith("/api/v1/devices/")) {
            String suffix = path.substring("/api/v1/devices/".length());
            String[] parts = suffix.split("/", -1);

            if (parts.length == 1) {
                long id = id(parts[0]);
                switch (method) {
                    case "GET" -> respond(exchange, 200, service.get(id).json());
                    case "PUT" -> respond(exchange, 200, service.update(id, body(exchange)).json());
                    case "DELETE" -> {
                        service.delete(id);
                        respond(exchange, 200, Map.of("message", "Sensor deleted successfully"));
                    }
                    default -> throw new ApiException(405, "Method not allowed");
                }

                return;
            }

            if (parts.length == 2 && parts[1].equals("commands")) {
                requireMethod(method, "POST");
                var command = service.command(id(parts[0]), body(exchange));
                exchange.getResponseHeaders().set("Location", "/api/v1/commands/" + command.id());
                respond(exchange, 201, command.json());
                return;
            }
        }

        if (path.startsWith("/api/v1/commands/")) {
            requireMethod(method, "GET");
            String id = path.substring("/api/v1/commands/".length());
            if (id.contains("/")) {
                throw new ApiException(404, "Route not found");
            }

            respond(exchange, 200, service.command(id).json());
            return;
        }

        throw new ApiException(404, "Route not found");
    }

    private static long id(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0 || !value.matches("[0-9]+")) {
                throw new NumberFormatException();
            }

            return id;
        } catch (NumberFormatException exception) {
            throw new ApiException(400, "Device ID must be a positive integer");
        }
    }

    private static void requireMethod(String actual, String expected) {
        if (!actual.equals(expected)) {
            throw new ApiException(405, "Method not allowed");
        }
    }

    private static Map<String, Object> body(HttpExchange exchange) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            throw new ApiException(400, "Content-Type application/json is required");
        }

        byte[] bytes = exchange.getRequestBody().readAllBytes();
        return Json.object(bytes);
    }

    private static String query(HttpExchange exchange, String key) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return null;
        }

        String result = null;
        try {
            for (String pair : raw.split("&")) {
                String[] item = pair.split("=", 2);
                String name = URLDecoder.decode(item[0], StandardCharsets.UTF_8);
                if (!name.equals(key)) {
                    throw new ApiException(400, "Unknown query parameter: " + name);
                }
                if (result != null) {
                    throw new ApiException(400, "Duplicate query parameter: " + name);
                }

                result = item.length == 2 ? URLDecoder.decode(item[1], StandardCharsets.UTF_8) : "";
            }

            return result;
        } catch (IllegalArgumentException exception) {
            throw new ApiException(400, "Invalid query encoding");
        }
    }

    private static void respond(HttpExchange exchange, int status, Object data) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsBytes(data);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(1);
        executor.close();
    }
}
