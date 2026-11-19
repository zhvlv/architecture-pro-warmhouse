package com.warmhouse.devices;

import java.util.Map;

/** The MVP adapter simulates an actuator and acknowledges the command synchronously. */
public final class DeviceState {
    private DeviceState() {}

    public static Map<String, Object> after(String type, String action) {
        return switch (type + ":" + action) {
            case "heating:turn_on", "light:turn_on" -> Map.of("power", "on");
            case "heating:turn_off", "light:turn_off" -> Map.of("power", "off");
            case "gate:open" -> Map.of("position", "open");
            case "gate:close" -> Map.of("position", "closed");
            case "camera:start_stream" -> Map.of("streaming", true);
            case "camera:stop_stream" -> Map.of("streaming", false);
            default -> throw new ApiException(400, "Action is not supported by this device type");
        };
    }
}
