package com.pgmanager.controller;

import com.pgmanager.security.AuthUser;
import com.pgmanager.security.JwtAuthHandler;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The first handler of every request. It gives the request an id and writes one log line when the response
 * is finished:
 *
 *   request id=4f1c... method=GET path=/api/tenants status=200 durationMs=12 user=9b2e...
 *
 * The id comes from the caller's X-Request-Id header when it looks safe (so a proxy or client can follow a
 * request across systems), otherwise a new UUID. It is sent back in X-Request-Id and appears in error logs.
 * Only the path is logged - never the query string, headers (no Authorization/JWT) or the body (no passwords).
 */
public class RequestLogHandler implements Handler<RoutingContext> {

    private static final Logger log = LoggerFactory.getLogger(RequestLogHandler.class);
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String REQUEST_ID_KEY = "requestId";
    /** Short and plain, so a client can't inject fake log lines or huge values. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    public void handle(RoutingContext ctx) {
        String incoming = ctx.request().getHeader(REQUEST_ID_HEADER);
        String requestId = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        ctx.put(REQUEST_ID_KEY, requestId);
        ctx.response().putHeader(REQUEST_ID_HEADER, requestId);

        long start = System.nanoTime();
        ctx.addEndHandler(done -> logRequest(ctx, requestId, (System.nanoTime() - start) / 1_000_000));
        ctx.next();
    }

    private static void logRequest(RoutingContext ctx, String requestId, long durationMs) {
        String path = ctx.request().path();
        AuthUser user = JwtAuthHandler.currentUser(ctx);
        String line = "request id={} method={} path={} status={} durationMs={} user={}";
        Object[] values = {requestId, ctx.request().method(), path, ctx.response().getStatusCode(), durationMs,
                user == null ? "-" : user.id()};
        if (path.equals("/api/health")) {
            // Called every few seconds by the Docker health check; only interesting while debugging
            log.debug(line, values);
        } else {
            log.info(line, values);
        }
    }

    /** The id of the current request (null outside a request). */
    public static String requestId(RoutingContext ctx) {
        return ctx.get(REQUEST_ID_KEY);
    }
}
