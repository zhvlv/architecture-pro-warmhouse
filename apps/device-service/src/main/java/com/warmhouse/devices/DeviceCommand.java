package com.warmhouse.devices;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DeviceCommand(
    UUID id,
    long deviceId,
    String action,
    String status,
    Instant createdAt,
    Instant completedAt,
    Map<String, Object> state
) {
    public DeviceCommand {
        state = Map.copyOf(state);
    }

    public Map<String, Object> json() {
        return Map.of(
            "id", id.toString(),
            "device_id", deviceId,
            "action", action,
            "status", status,
            "created_at", createdAt.toString(),
            "completed_at", completedAt.toString(),
            "state", state
        );
    }
}
