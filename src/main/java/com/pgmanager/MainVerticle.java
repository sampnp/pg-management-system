package com.pgmanager;

import com.pgmanager.config.AppConfig;
import com.pgmanager.config.Database;
import com.pgmanager.controller.AuthController;
import com.pgmanager.controller.HealthController;
import com.pgmanager.repository.UserRepository;
import com.pgmanager.security.JwtService;
import com.pgmanager.security.PasswordHasher;
import com.pgmanager.service.AuthService;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.sqlclient.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wires the application together (manual constructor injection) and starts the HTTP server.
 * Every step returns a Future, and compose() chains them so each step starts only after the previous one succeeds.
 */
public class MainVerticle extends VerticleBase {

    private static final Logger log = LoggerFactory.getLogger(MainVerticle.class);
    private static final long MAX_BODY_BYTES = 64 * 1024;

    private final AppConfig config;
    private Pool pool;

    public MainVerticle(AppConfig config) {
        this.config = config;
    }

    @Override
    public Future<?> start() {
        return Database.migrate(vertx, config.database())
                .compose(migrated -> {
                    pool = Database.createPool(vertx, config.database());
                    return vertx.createHttpServer()
                            .requestHandler(createRouter())
                            .listen(config.httpPort());
                })
                .onSuccess(server -> log.info("HTTP server listening on port {}", server.actualPort()));
    }

    @Override
    public Future<?> stop() {
        return pool != null ? pool.close() : Future.succeededFuture();
    }

    private Router createRouter() {
        // Manual dependency injection: build each object once and pass it to whoever needs it
        UserRepository userRepository = new UserRepository(pool);
        PasswordHasher passwordHasher = new PasswordHasher(PasswordHasher.DEFAULT_COST);
        JwtService jwtService = new JwtService(vertx, config.jwt());
        AuthService authService = new AuthService(vertx, userRepository, passwordHasher, jwtService);

        HealthController healthController = new HealthController(pool);
        AuthController authController = new AuthController(authService);

        Router router = Router.router(vertx);
        // BodyHandler must come first: it reads the request body before any async handler (like JWT checks) runs
        router.route("/api/*").handler(BodyHandler.create().setBodyLimit(MAX_BODY_BYTES));

        router.get("/api/health").handler(healthController::check);

        // Public
        router.post("/api/auth/register").handler(authController::register);
        router.post("/api/auth/login").handler(authController::login);
        return router;
    }
}
