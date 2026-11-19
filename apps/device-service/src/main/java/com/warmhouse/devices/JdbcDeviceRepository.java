package com.warmhouse.devices;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

public final class JdbcDeviceRepository implements DeviceRepository {
    private static final String COLUMNS =
        "id, house_id, serial_number, name, type, location, unit, status, state, created_at, last_updated";
    private final String jdbcUrl;
    private final Properties properties = new Properties();

    public JdbcDeviceRepository(String databaseUrl) {
        var uri = URI.create(databaseUrl.replaceFirst("^postgres://", "postgresql://"));
        if (!"postgresql".equals(uri.getScheme())
            || uri.getHost() == null
            || uri.getPath() == null
            || uri.getPath().length() < 2) {
            throw new IllegalArgumentException("DATABASE_URL must be a PostgreSQL URL including the database name");
        }

        String host = uri.getHost().contains(":") ? "[" + uri.getHost() + "]" : uri.getHost();
        jdbcUrl = "jdbc:postgresql://" + host + ":"
            + (uri.getPort() == -1 ? 5432 : uri.getPort()) + uri.getRawPath();

        if (uri.getRawQuery() != null) {
            throw new IllegalArgumentException("DATABASE_URL query parameters are not supported in the MVP");
        }

        if (uri.getRawUserInfo() != null) {
            String[] credentials = uri.getRawUserInfo().split(":", 2);
            properties.setProperty("user", URLDecoder.decode(credentials[0], StandardCharsets.UTF_8));
            if (credentials.length == 2) {
                properties.setProperty(
                    "password",
                    URLDecoder.decode(credentials[1], StandardCharsets.UTF_8)
                );
            }
        }

        properties.setProperty("connectTimeout", "3");
        properties.setProperty("socketTimeout", "5");
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, properties);
    }

    @Override
    public List<Device> list(String type) {
        String sql = "SELECT " + COLUMNS + " FROM devices WHERE deleted_at IS NULL"
            + (type == null ? "" : " AND type = ?") + " ORDER BY id";

        try (
            var connection = connection();
            var query = connection.prepareStatement(sql)
        ) {
            if (type != null) {
                query.setString(1, type);
            }

            try (var rows = query.executeQuery()) {
                var devices = new ArrayList<Device>();
                while (rows.next()) {
                    devices.add(device(rows));
                }

                return devices;
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @Override
    public Optional<Device> find(long id) {
        try (
            var connection = connection();
            var query = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM devices WHERE id = ? AND deleted_at IS NULL"
            )
        ) {
            query.setLong(1, id);
            try (var rows = query.executeQuery()) {
                return rows.next() ? Optional.of(device(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @Override
    public Device create(Device device) {
        String sql =
            "INSERT INTO devices(house_id, serial_number, name, type, location, unit, status, state, created_at, last_updated) VALUES (?,?,?,?,?,?,?,?::jsonb,?,?) RETURNING "
                + COLUMNS;

        try (
            var connection = connection();
            var query = connection.prepareStatement(sql)
        ) {
            query.setLong(1, device.houseId());
            query.setString(2, device.serialNumber());
            query.setString(3, device.name());
            query.setString(4, device.type());
            query.setString(5, device.location());
            query.setString(6, device.unit());
            query.setString(7, device.status());
            query.setString(8, encode(device.state()));
            query.setTimestamp(9, Timestamp.from(device.createdAt()));
            query.setTimestamp(10, Timestamp.from(device.lastUpdated()));
            try (var rows = query.executeQuery()) {
                rows.next();
                return device(rows);
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @Override
    public Device update(long id, DevicePatch patch, Instant now) {
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                Device current;
                try (var query = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM devices WHERE id=? AND deleted_at IS NULL FOR UPDATE"
                )) {
                    query.setLong(1, id);
                    try (var rows = query.executeQuery()) {
                        if (!rows.next()) {
                            throw new ApiException(404, "Device not found");
                        }

                        current = device(rows);
                    }
                }

                if (patch.empty()) {
                    connection.commit();
                    return current;
                }

                Device merged = patch.apply(current, now);
                String sql =
                    "UPDATE devices SET name=?, type=?, location=?, unit=?, status=?, state=?::jsonb, last_updated=? WHERE id=? RETURNING "
                        + COLUMNS;
                Device updated;
                try (var query = connection.prepareStatement(sql)) {
                    query.setString(1, merged.name());
                    query.setString(2, merged.type());
                    query.setString(3, merged.location());
                    query.setString(4, merged.unit());
                    query.setString(5, merged.status());
                    query.setString(6, encode(merged.state()));
                    query.setTimestamp(7, Timestamp.from(merged.lastUpdated()));
                    query.setLong(8, id);
                    try (var rows = query.executeQuery()) {
                        rows.next();
                        updated = device(rows);
                    }
                }

                connection.commit();
                return updated;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    exception.addSuppressed(rollbackError);
                }

                throw exception;
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @Override
    public boolean delete(long id, Instant now) {
        try (
            var connection = connection();
            var query = connection.prepareStatement(
                "UPDATE devices SET deleted_at=?, last_updated=? WHERE id=? AND deleted_at IS NULL"
            )
        ) {
            query.setTimestamp(1, Timestamp.from(now));
            query.setTimestamp(2, Timestamp.from(now));
            query.setLong(3, id);
            return query.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @Override
    public DeviceCommand issueCommand(long id, String action, Instant now) {
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                Device current;
                try (var query = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM devices WHERE id=? AND deleted_at IS NULL FOR UPDATE"
                )) {
                    query.setLong(1, id);
                    try (var rows = query.executeQuery()) {
                        if (!rows.next()) {
                            throw new ApiException(404, "Device not found");
                        }

                        current = device(rows);
                    }
                }

                var state = DeviceState.after(current.type(), action);
                var command = new DeviceCommand(
                    UUID.randomUUID(),
                    id,
                    action,
                    "completed",
                    now,
                    now,
                    state
                );

                try (var query = connection.prepareStatement(
                    "UPDATE devices SET state=?::jsonb, status='active', last_updated=? WHERE id=?"
                )) {
                    query.setString(1, encode(state));
                    query.setTimestamp(2, Timestamp.from(now));
                    query.setLong(3, id);
                    query.executeUpdate();
                }

                try (var query = connection.prepareStatement(
                    "INSERT INTO device_commands(id,device_id,action,status,created_at,completed_at,state) VALUES(?,?,?,?,?,?,?::jsonb)"
                )) {
                    query.setObject(1, command.id());
                    query.setLong(2, id);
                    query.setString(3, action);
                    query.setString(4, "completed");
                    query.setTimestamp(5, Timestamp.from(now));
                    query.setTimestamp(6, Timestamp.from(now));
                    query.setString(7, encode(state));
                    query.executeUpdate();
                }

                connection.commit();
                return command;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    exception.addSuppressed(rollbackError);
                }

                throw exception;
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @Override
    public Optional<DeviceCommand> findCommand(UUID id) {
        try (
            var connection = connection();
            var query = connection.prepareStatement(
                "SELECT id,device_id,action,status,created_at,completed_at,state FROM device_commands WHERE id=?"
            )
        ) {
            query.setObject(1, id);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }

                return Optional.of(new DeviceCommand(
                    rows.getObject("id", UUID.class),
                    rows.getLong("device_id"),
                    rows.getString("action"),
                    rows.getString("status"),
                    rows.getTimestamp("created_at").toInstant(),
                    rows.getTimestamp("completed_at").toInstant(),
                    Json.object(rows.getString("state"))
                ));
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @Override
    public boolean healthy() {
        try (
            var connection = connection();
            var query = connection.prepareStatement("SELECT 1")
        ) {
            query.setQueryTimeout(2);
            try (var rows = query.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException exception) {
            return false;
        }
    }

    private static Device device(ResultSet row) throws SQLException {
        return new Device(
            row.getLong("id"),
            row.getLong("house_id"),
            row.getString("serial_number"),
            row.getString("name"),
            row.getString("type"),
            row.getString("location"),
            row.getString("unit"),
            row.getString("status"),
            Json.object(row.getString("state")),
            row.getTimestamp("created_at").toInstant(),
            row.getTimestamp("last_updated").toInstant()
        );
    }

    private static String encode(Object value) {
        try {
            return Json.MAPPER.writeValueAsString(value);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot serialize device state", exception);
        }
    }

    private static ApiException databaseError(SQLException exception) {
        if ("23505".equals(exception.getSQLState())) {
            return new ApiException(400, "serial_number already exists");
        }

        System.err.println("Device database operation failed (SQLSTATE " + exception.getSQLState() + ")");
        return new ApiException(503, "Device database is unavailable");
    }
}
