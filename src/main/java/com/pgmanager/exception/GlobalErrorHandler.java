package com.pgmanager.exception;

import com.pgmanager.controller.RequestLogHandler;
import io.vertx.core.Handler;
import io.vertx.core.json.DecodeException;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.HttpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns every failure into a consistent JSON error response.
 * Vert.x calls this when a handler calls ctx.fail(...), throws an exception, or when no route matches (404/405).
 * Clients only ever see safe messages; full details go to the log.
 */
public class GlobalErrorHandler implements Handler<RoutingContext> {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    @Override
    public void handle(RoutingContext ctx) {
        if (ctx.response().ended()) {
            return;
        }

        ApiError error = switch (ctx.failure()) {
            case TooManyRequestsException e -> {
                ctx.response().putHeader("Retry-After", String.valueOf(e.retryAfterSeconds()));
                yield ApiError.of(e.statusCode(), e.getMessage());
            }
            case ApiException e -> ApiError.of(e.statusCode(), e.getMessage());
            case DecodeException e -> ApiError.of(400, "Malformed JSON request body");
            // Vert.x itself failed the request with a status code (404 no route, 405, 413 body too large...)
            case HttpException e -> ApiError.of(e.getStatusCode(), defaultMessage(e.getStatusCode()));
            case null -> ApiError.of(statusOrDefault(ctx.statusCode()), defaultMessage(ctx.statusCode()));
            // A Vert.x handler refused the request with a 4xx status and an exception as the reason
            // (e.g. CorsHandler: 403 for an origin that is not allowed). The client's fault, not a server error.
            case Throwable e when ctx.statusCode() >= 400 && ctx.statusCode() < 500 -> {
                log.debug("Request refused with {}: {}", ctx.statusCode(), e.getMessage());
                yield ApiError.of(ctx.statusCode(), defaultMessage(ctx.statusCode()));
            }
            default -> {
                log.error("Unhandled error on {} {} (request id={})", ctx.request().method(), ctx.request().path(),
                        RequestLogHandler.requestId(ctx), ctx.failure());
                yield ApiError.of(500, "Internal server error");
            }
        };

        ctx.response().setStatusCode(error.status());
        ctx.json(error);
    }

    private static int statusOrDefault(int statusCode) {
        return statusCode > 0 ? statusCode : 500;
    }

    private static String defaultMessage(int statusCode) {
        return switch (statusCode) {
            case 400 -> "Bad request";
            case 401 -> "Authentication required";
            case 403 -> "Insufficient permissions";
            case 404 -> "Resource not found";
            case 405 -> "Method not allowed";
            case 413 -> "Request body too large";
            default -> "Request failed";
        };
    }
}
