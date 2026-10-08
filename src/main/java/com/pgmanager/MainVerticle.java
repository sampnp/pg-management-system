package com.pgmanager;

import com.pgmanager.config.AppConfig;
import com.pgmanager.config.Database;
import com.pgmanager.controller.AuthController;
import com.pgmanager.controller.BedController;
import com.pgmanager.controller.HealthController;
import com.pgmanager.controller.PropertyController;
import com.pgmanager.controller.RoomController;
import com.pgmanager.exception.GlobalErrorHandler;
import com.pgmanager.model.Role;
import com.pgmanager.repository.BedRepository;
import com.pgmanager.repository.PropertyRepository;
import com.pgmanager.repository.RoomRepository;
import com.pgmanager.repository.UserRepository;
import com.pgmanager.security.JwtAuthHandler;
import com.pgmanager.security.JwtService;
import com.pgmanager.security.PasswordHasher;
import com.pgmanager.security.RoleHandler;
import com.pgmanager.service.AuthService;
import com.pgmanager.service.BedService;
import com.pgmanager.service.PropertyService;
import com.pgmanager.service.RoomService;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.sqlclient.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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

        PropertyRepository propertyRepository = new PropertyRepository(pool);
        RoomRepository roomRepository = new RoomRepository(pool);
        BedRepository bedRepository = new BedRepository(pool);
        PropertyService propertyService = new PropertyService(propertyRepository);
        RoomService roomService = new RoomService(propertyRepository, roomRepository, bedRepository);
        BedService bedService = new BedService(roomRepository, bedRepository);

        HealthController healthController = new HealthController(pool);
        AuthController authController = new AuthController(authService);
        PropertyController propertyController = new PropertyController(propertyService);
        RoomController roomController = new RoomController(roomService);
        BedController bedController = new BedController(bedService);
        JwtAuthHandler jwtAuth = new JwtAuthHandler(jwtService);
        Handler<RoutingContext> staffOnly = RoleHandler.requireRole(Role.ADMIN, Role.MANAGER);
        GlobalErrorHandler errorHandler = new GlobalErrorHandler();

        Router router = Router.router(vertx);
        // BodyHandler must come first: it reads the request body before any async handler (like JWT checks) runs
        router.route("/api/*").handler(BodyHandler.create().setBodyLimit(MAX_BODY_BYTES));

        router.get("/api/health").handler(healthController::check);

        // Public
        router.post("/api/auth/register").handler(authController::register);
        router.post("/api/auth/login").handler(authController::login);

        // Protected: each handler runs in order and calls ctx.next() to pass the request on
        router.get("/api/auth/me").handler(jwtAuth).handler(authController::me);
        router.get("/api/admin/test")
                .handler(jwtAuth)
                .handler(RoleHandler.requireRole(Role.ADMIN))
                .handler(ctx -> ctx.json(new JsonObject().put("message", "Admin access granted")));

        // Property/room/bed management: protect each whole path prefix once, so every endpoint
        // under it (including ones added later) requires a valid JWT and an ADMIN or MANAGER role
        for (String protectedPath : List.of("/api/properties*", "/api/rooms*", "/api/beds*")) {
            router.route(protectedPath).handler(jwtAuth).handler(staffOnly);
        }

        router.post("/api/properties").handler(propertyController::create);
        router.get("/api/properties").handler(propertyController::list);
        router.get("/api/properties/:id").handler(propertyController::get);
        router.put("/api/properties/:id").handler(propertyController::update);
        router.delete("/api/properties/:id").handler(propertyController::delete);

        router.post("/api/properties/:propertyId/rooms").handler(roomController::create);
        router.get("/api/properties/:propertyId/rooms").handler(roomController::listByProperty);
        router.get("/api/rooms/:id").handler(roomController::get);
        router.put("/api/rooms/:id").handler(roomController::update);
        router.delete("/api/rooms/:id").handler(roomController::delete);

        router.post("/api/rooms/:roomId/beds").handler(bedController::create);
        router.get("/api/rooms/:roomId/beds").handler(bedController::listByRoom);
        router.get("/api/beds/:id").handler(bedController::get);
        router.put("/api/beds/:id").handler(bedController::update);
        router.patch("/api/beds/:id/status").handler(bedController::updateStatus);
        router.delete("/api/beds/:id").handler(bedController::delete);

        // Errors: failures from any route, plus "no route matched" (404) and "wrong method" (405)
        router.route().failureHandler(errorHandler);
        router.errorHandler(404, errorHandler);
        router.errorHandler(405, errorHandler);
        return router;
    }
}
