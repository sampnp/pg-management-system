package com.pgmanager.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.vertx.core.json.jackson.DatabindCodec;

/** Configures the Jackson ObjectMapper that Vert.x uses for ctx.json(...) and asPojo(...). */
public final class JsonConfig {

    private JsonConfig() {
    }

    public static void configure() {
        // Without this, LocalDate would fail to serialize (or become an array like [2026,10,10]).
        // With it, dates are written as ISO strings: "2026-10-10".
        DatabindCodec.mapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
