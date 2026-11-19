package com.warmhouse.devices;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DeviceService {
    public static final Set<String> TYPES = Set.of("temperature", "heating", "light", "gate", "camera");
    private static final Set<String> STATUSES = Set.of("active", "inactive", "error");
    private static final Set<String> CREATE_FIELDS = Set.of(
            "name", "type", "location", "unit", "house_id", "serial_number");
    private static final Set<String> UPDATE_FIELDS = Set.of(
            "name", "type", "location", "unit", "status", "value");
    private final DeviceRepository repository;
    private final Clock clock;

    public DeviceService(DeviceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<Device> list(String type) {
        if (type != null && !TYPES.contains(type)) {
            throw new ApiException(400, "Unsupported device type");
        }

        return repository.list(type);
    }

    public Device get(long id) {
        requireId(id);
        return repository.find(id).orElseThrow(() -> new ApiException(404, "Device not found"));
    }

    public Device create(Map<String, Object> body) {
        fields(body, CREATE_FIELDS);

        String name = text(body, "name", 100, false);
        String type = type(text(body, "type", 30, false));
        String location = text(body, "location", 100, false);
        String unit = body.containsKey("unit")
                ? text(body, "unit", 20, true)
                : (type.equals("temperature") ? "°C" : "");
        long houseId = body.containsKey("house_id")
                ? positiveInteger(body.get("house_id"), "house_id")
                : 1;
        String serial = body.containsKey("serial_number")
                ? text(body, "serial_number", 100, false)
                : UUID.randomUUID().toString();
        var now = now();

        return repository.create(new Device(
                0, houseId, serial, name, type, location, unit, "inactive", Map.of(), now, now));
    }

    public Device update(long id, Map<String, Object> body) {
        requireId(id);
        fields(body, UPDATE_FIELDS);
        if (body.isEmpty()) {
            throw new ApiException(400, "At least one update field is required");
        }

        if (body.containsKey("value")) {
            Object value = body.get("value");
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                throw new ApiException(400, "value must be a finite number");
            }
        }

        String name = body.containsKey("name") ? text(body, "name", 100, false) : null;
        String type = body.containsKey("type") ? type(text(body, "type", 30, false)) : null;
        String location = body.containsKey("location") ? text(body, "location", 100, false) : null;
        String unit = body.containsKey("unit") ? text(body, "unit", 20, true) : null;
        String status = body.containsKey("status") ? text(body, "status", 20, false) : null;
        if (status != null && !STATUSES.contains(status)) {
            throw new ApiException(400, "Unsupported device status");
        }

        return repository.update(id, new DevicePatch(name, type, location, unit, status), now());
    }

    public void delete(long id) {
        requireId(id);
        if (!repository.delete(id, now())) {
            throw new ApiException(404, "Device not found");
        }
    }

    public DeviceCommand command(long id, Map<String, Object> body) {
        requireId(id);
        fields(body, Set.of("action"));

        String action = text(body, "action", 30, false);
        return repository.issueCommand(id, action, now());
    }

    public DeviceCommand command(String id) {
        UUID parsed;
        try {
            parsed = UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(400, "Invalid command ID");
        }

        return repository.findCommand(parsed).orElseThrow(() -> new ApiException(404, "Command not found"));
    }

    public boolean healthy() {
        return repository.healthy();
    }

    private Instant now() {
        // Match PostgreSQL timestamp precision so POST and subsequent GET agree exactly.
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static void fields(Map<String, Object> body, Set<String> allowed) {
        for (String field : body.keySet()) {
            if (!allowed.contains(field)) {
                throw new ApiException(400, "Unknown field: " + field);
            }
        }
    }

    private static String text(Map<String, Object> body, String field, int maxLength, boolean emptyAllowed) {
        if (!(body.get(field) instanceof String value)) {
            throw new ApiException(400, field + " must be a string");
        }

        value = value.strip();
        if ((!emptyAllowed && value.isEmpty()) || value.length() > maxLength) {
            throw new ApiException(400, "Invalid " + field);
        }

        return value;
    }

    private static String type(String value) {
        if (!TYPES.contains(value)) {
            throw new ApiException(400, "Unsupported device type");
        }

        return value;
    }

    private static long positiveInteger(Object value, String name) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
            throw new ApiException(400, name + " must be a positive integer");
        }

        long number = ((Number) value).longValue();
        if (number <= 0) {
            throw new ApiException(400, name + " must be a positive integer");
        }

        return number;
    }

    private static void requireId(long id) {
        if (id <= 0) {
            throw new ApiException(400, "Device ID must be positive");
        }
    }
}
