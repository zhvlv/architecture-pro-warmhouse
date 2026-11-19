package com.warmhouse.devices;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository {
    List<Device> list(String type);
    Optional<Device> find(long id);
    Device create(Device device);
    Device update(long id, DevicePatch patch, Instant now);
    boolean delete(long id, Instant now);
    DeviceCommand issueCommand(long deviceId, String action, Instant now);
    Optional<DeviceCommand> findCommand(UUID id);
    boolean healthy();
}
