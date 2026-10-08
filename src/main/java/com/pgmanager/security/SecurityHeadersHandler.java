package com.pgmanager.security;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;

/**
 * Adds standard security headers to every response, including error responses. This is a JSON API with no
 * HTML pages, so the strictest settings cost nothing.
 * Strict-Transport-Security is not set here: HTTPS is ended by the reverse proxy / load balancer in front
 * of the app, which is the right place for it.
 */
public class SecurityHeadersHandler implements Handler<RoutingContext> {

    @Override
    public void handle(RoutingContext ctx) {
        HttpServerResponse response = ctx.response();
        response.putHeader("X-Content-Type-Options", "nosniff");      // browsers must trust Content-Type
        response.putHeader("X-Frame-Options", "DENY");                 // never shown inside a frame
        response.putHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        response.putHeader("Referrer-Policy", "no-referrer");
        // Responses contain personal data (tenants, payments): don't keep them in browser or proxy caches
        response.putHeader("Cache-Control", "no-store");
        ctx.next();
    }
}
