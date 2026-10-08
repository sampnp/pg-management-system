package com.pgmanager;

import com.pgmanager.config.AppConfig;
import com.pgmanager.config.Cache;
import com.pgmanager.config.Database;
import com.pgmanager.config.JsonConfig;
import com.pgmanager.config.SecurityConfig;
import com.pgmanager.controller.AuthController;
import com.pgmanager.controller.BedController;
import com.pgmanager.controller.DashboardController;
import com.pgmanager.controller.HealthController;
import com.pgmanager.controller.MaintenanceController;
import com.pgmanager.controller.OccupancyController;
import com.pgmanager.controller.PaymentController;
import com.pgmanager.controller.PropertyController;
import com.pgmanager.controller.RoomController;
import com.pgmanager.controller.TenantController;
import com.pgmanager.controller.UserController;
import com.pgmanager.exception.GlobalErrorHandler;
import com.pgmanager.model.Role;
import com.pgmanager.repository.BedRepository;
import com.pgmanager.repository.DashboardCache;
import com.pgmanager.repository.DashboardRepository;
import com.pgmanager.repository.MaintenanceRepository;
import com.pgmanager.repository.PaymentRepository;
import com.pgmanager.repository.PropertyRepository;
import com.pgmanager.repository.RoomRepository;
import com.pgmanager.repository.TenantBedHistoryRepository;
import com.pgmanager.repository.TenantRepository;
import com.pgmanager.repository.UserRepository;
import com.pgmanager.security.JwtAuthHandler;
import com.pgmanager.security.JwtService;
import com.pgmanager.security.PasswordHasher;
import com.pgmanager.security.RoleHandler;
import com.pgmanager.service.AuthService;
import com.pgmanager.service.BedService;
import com.pgmanager.service.DashboardService;
import com.pgmanager.service.MaintenanceService;
import com.pgmanager.service.OccupancyService;
import com.pgmanager.service.PaymentService;
import com.pgmanager.service.PropertyService;
import com.pgmanager.service.RoomService;
import com.pgmanager.service.TenantService;
import com.pgmanager.service.UserService;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
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
    private Redis redis;
    private AuthService authService;

    public MainVerticle(AppConfig config) {
        this.config = config;
    }

    @Override
    public Future<?> start() {
        JsonConfig.configure();
        return Database.migrate(vertx, config.database())
                .compose(migrated -> {
                    pool = Database.createPool(vertx, config.database());
                    redis = Cache.createClient(vertx, config.redis());
                    Router router = createRouter();
                    return createFirstAdminIfConfigured()
                            .compose(v -> vertx.createHttpServer()
                                    .requestHandler(router)
                                    .listen(config.httpPort()));
                })
                .onSuccess(server -> log.info("HTTP server listening on port {}", server.actualPort()));
    }

    private Future<Void> createFirstAdminIfConfigured() {
        SecurityConfig security = config.security();
        return security.hasBootstrapAdmin()
                ? authService.createFirstAdmin(security.bootstrapAdminEmail(), security.bootstrapAdminPassword())
                : Future.succeededFuture();
    }

    @Override
    public Future<?> stop() {
        return Future.all(
                pool != null ? pool.close() : Future.succeededFuture(),
                redis != null ? redis.close() : Future.succeededFuture());
    }

    private Router createRouter() {
        // Manual dependency injection: build each object once and pass it to whoever needs it
        UserRepository userRepository = new UserRepository(pool);
        TenantRepository tenantRepository = new TenantRepository(pool);
        PasswordHasher passwordHasher = new PasswordHasher(PasswordHasher.DEFAULT_COST);
        JwtService jwtService = new JwtService(vertx, config.jwt());
        authService = new AuthService(vertx, userRepository, tenantRepository, passwordHasher, jwtService,
                config.security().allowPublicRegistration());
        UserService userService = new UserService(userRepository);

        // Cleared by every service whose writes change a number on the dashboard
        DashboardCache dashboardCache = new DashboardCache(RedisAPI.api(redis), config.redis().dashboardCacheTtlSeconds());

        PropertyRepository propertyRepository = new PropertyRepository(pool);
        RoomRepository roomRepository = new RoomRepository(pool);
        BedRepository bedRepository = new BedRepository(pool);
        TenantBedHistoryRepository historyRepository = new TenantBedHistoryRepository();
        PaymentRepository paymentRepository = new PaymentRepository(pool);
        MaintenanceRepository maintenanceRepository = new MaintenanceRepository(pool);
        DashboardRepository dashboardRepository = new DashboardRepository(pool);
        PropertyService propertyService = new PropertyService(propertyRepository, dashboardCache);
        RoomService roomService = new RoomService(propertyRepository, roomRepository, bedRepository, dashboardCache);
        BedService bedService = new BedService(pool, roomRepository, bedRepository, historyRepository, dashboardCache);
        TenantService tenantService = new TenantService(tenantRepository, dashboardCache);
        OccupancyService occupancyService = new OccupancyService(pool, tenantRepository, bedRepository, historyRepository, dashboardCache);
        PaymentService paymentService = new PaymentService(paymentRepository, tenantRepository, dashboardCache);
        MaintenanceService maintenanceService = new MaintenanceService(maintenanceRepository, tenantRepository, userRepository, dashboardCache);
        DashboardService dashboardService = new DashboardService(dashboardRepository, dashboardCache);

        HealthController healthController = new HealthController(pool);
        AuthController authController = new AuthController(authService);
        UserController userController = new UserController(userService);
        PropertyController propertyController = new PropertyController(propertyService);
        RoomController roomController = new RoomController(roomService);
        BedController bedController = new BedController(bedService);
        TenantController tenantController = new TenantController(tenantService);
        OccupancyController occupancyController = new OccupancyController(occupancyService);
        PaymentController paymentController = new PaymentController(paymentService);
        MaintenanceController maintenanceController = new MaintenanceController(maintenanceService);
        DashboardController dashboardController = new DashboardController(dashboardService);
        JwtAuthHandler jwtAuth = new JwtAuthHandler(jwtService);
        Handler<RoutingContext> staffOnly = RoleHandler.requireRole(Role.ADMIN, Role.MANAGER);
        GlobalErrorHandler errorHandler = new GlobalErrorHandler();

        Router router = Router.router(vertx);
        // BodyHandler must come first: it reads the request body before any async handler (like JWT checks) runs
        router.route("/api/*").handler(BodyHandler.create().setBodyLimit(MAX_BODY_BYTES));

        router.get("/api/health").handler(healthController::check);

        // Public (registration answers 403 unless ALLOW_PUBLIC_REGISTRATION=true)
        router.post("/api/auth/register").handler(authController::register);
        router.post("/api/auth/login").handler(authController::login);

        // Protected: each handler runs in order and calls ctx.next() to pass the request on
        router.get("/api/auth/me").handler(jwtAuth).handler(authController::me);

        // Admin only: every endpoint under /api/admin requires a valid JWT with the ADMIN role
        router.route("/api/admin*").handler(jwtAuth).handler(RoleHandler.requireRole(Role.ADMIN));
        router.get("/api/admin/test").handler(ctx -> ctx.json(new JsonObject().put("message", "Admin access granted")));
        router.post("/api/admin/users").handler(authController::createStaffAccount);
        router.patch("/api/admin/users/:id/role").handler(userController::changeRole);

        // A tenant may read their own maintenance history, so this route is added BEFORE the staff-only
        // /api/tenants* guard below. Vert.x runs matching routes in the order they were added, and this route
        // ends the request, so the guard never runs for it. MaintenanceService checks that it is their own history.
        router.get("/api/tenants/:tenantId/maintenance").handler(jwtAuth).handler(maintenanceController::tenantHistory);

        // Property/room/bed/tenant/payment management and the dashboard: protect each whole path prefix once, so
        // every endpoint under it (including ones added later) requires a valid JWT and an ADMIN or MANAGER role
        for (String protectedPath : List.of("/api/properties*", "/api/rooms*", "/api/beds*", "/api/tenants*", "/api/payments*",
                "/api/dashboard*")) {
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

        router.post("/api/tenants").handler(tenantController::create);
        router.get("/api/tenants").handler(tenantController::list);
        router.get("/api/tenants/:id").handler(tenantController::get);
        router.put("/api/tenants/:id").handler(tenantController::update);
        router.delete("/api/tenants/:id").handler(tenantController::delete);

        router.post("/api/tenants/:tenantId/check-in").handler(occupancyController::checkIn);
        router.post("/api/tenants/:tenantId/check-out").handler(occupancyController::checkOut);
        router.get("/api/tenants/:tenantId/bed").handler(occupancyController::currentBed);
        router.get("/api/tenants/:tenantId/history").handler(occupancyController::history);
        router.post("/api/tenants/:tenantId/account").handler(authController::createTenantAccount);

        router.post("/api/payments").handler(paymentController::create);
        router.get("/api/payments").handler(paymentController::list);
        router.get("/api/payments/:id").handler(paymentController::get);
        router.put("/api/payments/:id").handler(paymentController::update);
        router.get("/api/tenants/:tenantId/payments").handler(paymentController::tenantHistory);

        // Maintenance: tenants use it too, so the whole prefix only requires a valid JWT. Staff-only actions add
        // the role check per route; "is this the tenant's own issue?" is checked in MaintenanceService.
        router.route("/api/maintenance*").handler(jwtAuth);
        router.post("/api/maintenance").handler(maintenanceController::create);
        router.get("/api/maintenance").handler(staffOnly).handler(maintenanceController::list);
        router.get("/api/maintenance/:id").handler(maintenanceController::get);
        router.put("/api/maintenance/:id").handler(maintenanceController::update);
        router.patch("/api/maintenance/:id/assign").handler(staffOnly).handler(maintenanceController::assign);
        router.patch("/api/maintenance/:id/status").handler(staffOnly).handler(maintenanceController::changeStatus);

        router.get("/api/dashboard").handler(dashboardController::get);

        // Errors: failures from any route, plus "no route matched" (404) and "wrong method" (405)
        router.route().failureHandler(errorHandler);
        router.errorHandler(404, errorHandler);
        router.errorHandler(405, errorHandler);
        return router;
    }
}
