package com.warmhouse.devices;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record Device(
    long id,
    long houseId,
    String serialNumber,
    String name,
    String type,
    String location,
    String unit,
    String status,
    Map<String, Object> state,
    Instant createdAt,
    Instant lastUpdated
) {
    public Device {
        state = Map.copyOf(state);
    }

    public Map<String, Object> json() {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("house_id", houseId);
        result.put("serial_number", serialNumber);
        result.put("name", name);
        result.put("type", type);
        result.put("location", location);
        result.put("unit", unit);
        result.put("status", status);
        result.put("state", state);
        result.put("created_at", createdAt.toString());
        result.put("last_updated", lastUpdated.toString());

        return result;
    }
}
