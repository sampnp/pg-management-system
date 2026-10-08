package com.pgmanager.config;

public record DatabaseConfig(String host, int port, String name, String user, String password) {

    /** Flyway needs a JDBC URL; the reactive client uses the individual fields instead. */
    public String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(host, port, name);
    }

    // Records generate toString() with every field. Override it so the password never ends up in logs.
    @Override
    public String toString() {
        return "DatabaseConfig[host=%s, port=%d, name=%s, user=%s, password=***]".formatted(host, port, name, user);
    }
}
