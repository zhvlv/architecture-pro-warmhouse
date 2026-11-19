package com.warmhouse.devices;

import java.net.InetSocketAddress;
import java.time.Clock;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        String databaseUrl = environment(
                "DATABASE_URL",
                "postgres://devices:devices@localhost:5432/devices");
        int port = Integer.parseInt(environment("PORT", "8082"));
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("PORT must be between 1 and 65535");
        }

        var repository = new JdbcDeviceRepository(databaseUrl);
        var service = new DeviceService(repository, Clock.systemUTC());
        var server = new ApiServer(new InetSocketAddress("0.0.0.0", port), service);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));

        server.start();
        System.out.println("Device service listening on port " + port);
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
