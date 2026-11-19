package com.warmhouse.devices;

import java.time.Instant;
import java.util.Map;

/** Validated explicit fields; omitted fields are merged only after the row is locked. */
public record DevicePatch(
    String name,
    String type,
    String location,
    String unit,
    String status
) {
    public boolean empty() {
        return name == null && type == null && location == null && unit == null && status == null;
    }

    public Device apply(Device current, Instant now) {
        if (empty()) {
            return current;
        }

        String nextType = type == null ? current.type() : type;
        var state = nextType.equals(current.type()) ? current.state() : Map.<String, Object>of();

        return new Device(
            current.id(),
            current.houseId(),
            current.serialNumber(),
            name == null ? current.name() : name,
            nextType,
            location == null ? current.location() : location,
            unit == null ? current.unit() : unit,
            status == null ? current.status() : status,
            state,
            current.createdAt(),
            now
        );
    }
}
