package com.pgmanager.config;

/** Redis connection settings, plus how long the dashboard may be served from the cache. */
public record RedisConfig(String host, int port, int dashboardCacheTtlSeconds) {

    public RedisConfig {
        if (dashboardCacheTtlSeconds <= 0) {
            throw new IllegalStateException("DASHBOARD_CACHE_TTL_SECONDS must be positive");
        }
    }

    public String connectionString() {
        return "redis://" + host + ":" + port;
    }
}
