package com.pgmanager.controller;

import com.pgmanager.exception.BadRequestException;
import io.vertx.ext.web.RoutingContext;

import java.util.UUID;

final class PathParams {

    private PathParams() {
    }

    /** Reads a UUID path parameter such as /api/rooms/:id. A malformed id is a 400, not a database error. */
    static UUID uuid(RoutingContext ctx, String name) {
        String value = ctx.pathParam(name);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException(name + " must be a valid UUID");
        }
    }
}
