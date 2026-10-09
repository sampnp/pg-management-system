package com.pgmanager.service;

import com.pgmanager.dto.ChangePasswordRequest;
import com.pgmanager.dto.CreateUserRequest;
import com.pgmanager.dto.LoginRequest;
import com.pgmanager.dto.RegisterRequest;
import com.pgmanager.dto.TenantAccountRequest;
import com.pgmanager.exception.BadRequestException;
import com.pgmanager.exception.ConflictException;
import com.pgmanager.exception.ForbiddenException;
import com.pgmanager.exception.NotFoundException;
import com.pgmanager.exception.TooManyRequestsException;
import com.pgmanager.exception.UnauthorizedException;
import com.pgmanager.model.Role;
import com.pgmanager.model.User;
import com.pgmanager.repository.TenantRepository;
import com.pgmanager.repository.UserRepository;
import com.pgmanager.security.AuthUser;
import com.pgmanager.security.JwtService;
import com.pgmanager.security.LoginRateLimiter;
import com.pgmanager.security.PasswordHasher;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Creates accounts and logs users in. */
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    static final String INVALID_CREDENTIALS = "Invalid email or password";
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_EMAIL_LENGTH = 255;

    private final Vertx vertx;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordHasher passwordHasher;
    private final JwtService jwtService;
    private final boolean allowPublicRegistration;
    private final LoginRateLimiter loginRateLimiter;
    /** Checked when the email is unknown, so "unknown email" and "wrong password" take the same time. */
    private final String dummyHash;

    public AuthService(Vertx vertx, UserRepository userRepository, TenantRepository tenantRepository,
                       PasswordHasher passwordHasher, JwtService jwtService, boolean allowPublicRegistration,
                       LoginRateLimiter loginRateLimiter) {
        this.vertx = vertx;
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.passwordHasher = passwordHasher;
        this.jwtService = jwtService;
        this.allowPublicRegistration = allowPublicRegistration;
        this.loginRateLimiter = loginRateLimiter;
        this.dummyHash = passwordHasher.hash(UUID.randomUUID().toString());
    }

    /**
     * Public registration. Disabled unless ALLOW_PUBLIC_REGISTRATION=true, because a MANAGER can see all PG
     * data; normally an ADMIN creates staff accounts (createStaffAccount). When enabled it always creates a
     * MANAGER: the caller cannot choose a role, so nobody can make themselves ADMIN here.
     */
    public Future<User> register(RegisterRequest request) {
        if (!allowPublicRegistration) {
            return Future.failedFuture(new ForbiddenException(
                    "Public registration is disabled. Ask an administrator to create your account"));
        }
        String validationError = validateRegistration(request);
        if (validationError != null) {
            return Future.failedFuture(new BadRequestException(validationError));
        }

        String email = normalizeEmail(request.email());
        return hashIfEmailIsFree(email, request.password())
                .compose(hash -> userRepository.insert(request.name().trim(), email, hash, Role.MANAGER));
    }

    /** An ADMIN creates a staff account (POST /api/admin/users). The route checks that the caller is ADMIN. */
    public Future<User> createStaffAccount(CreateUserRequest request) {
        return Future.succeededFuture(request)
                .map(r -> {
                    String validationError = validateRegistration(
                            r == null ? null : new RegisterRequest(r.name(), r.email(), r.password()));
                    if (validationError != null) {
                        throw new BadRequestException(validationError);
                    }
                    return Validation.parseStaffRole(r.role());
                })
                .compose(role -> {
                    String email = normalizeEmail(request.email());
                    return hashIfEmailIsFree(email, request.password())
                            .compose(hash -> userRepository.insert(request.name().trim(), email, hash, role));
                });
    }

    /**
     * Creates the first ADMIN at startup from BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD, so a fresh
     * deployment can be used without public registration or manual SQL. Does nothing once any ADMIN exists,
     * so the variables are harmless on later restarts (they should still be removed after the first start).
     * An invalid email or password stops the startup, because it is a configuration mistake.
     */
    public Future<Void> createFirstAdmin(String email, String password) {
        String validationError = credentialsError(email, password);
        if (validationError != null) {
            return Future.failedFuture(new IllegalStateException("Invalid BOOTSTRAP_ADMIN settings: " + validationError));
        }
        String normalized = normalizeEmail(email);
        return userRepository.adminExists()
                .compose(adminExists -> {
                    if (adminExists) {
                        log.info("An ADMIN already exists; BOOTSTRAP_ADMIN_* is ignored and can be removed");
                        return Future.succeededFuture();
                    }
                    return userRepository.findByEmail(normalized)
                            .compose(existing -> {
                                if (existing.isPresent()) {
                                    // Never turn an existing (possibly self-registered) account into an ADMIN
                                    log.warn("BOOTSTRAP_ADMIN_EMAIL {} already belongs to an account; no ADMIN was created", normalized);
                                    return Future.succeededFuture();
                                }
                                return vertx.executeBlocking(() -> passwordHasher.hash(password), false)
                                        .compose(hash -> userRepository.insert("Administrator", normalized, hash, Role.ADMIN))
                                        .onSuccess(admin -> log.info("Created the first ADMIN account: {}", admin.email()))
                                        .mapEmpty();
                            });
                });
    }

    /**
     * Creates the login of a tenant (called by staff). The account gets the TENANT role and the tenant's name,
     * and can only see that tenant's data. A tenant can have at most one account.
     */
    public Future<User> createTenantAccount(UUID tenantId, TenantAccountRequest request) {
        String validationError = request == null ? "Request body is required" : credentialsError(request.email(), request.password());
        if (validationError != null) {
            return Future.failedFuture(new BadRequestException(validationError));
        }

        String email = normalizeEmail(request.email());
        return tenantRepository.findById(tenantId)
                .map(tenant -> tenant.orElseThrow(() -> new NotFoundException(TenantService.TENANT_NOT_FOUND)))
                .compose(tenant -> hashIfEmailIsFree(email, request.password())
                        .compose(hash -> userRepository.insertTenantUser(tenant.name(), email, hash, tenantId)));
    }

    private Future<String> hashIfEmailIsFree(String email, String password) {
        return userRepository.findByEmail(email)
                .compose(existing -> {
                    if (existing.isPresent()) {
                        return Future.failedFuture(new ConflictException("Email already exists"));
                    }
                    // BCrypt is deliberately slow, so hash on a worker thread. 'false' = unordered,
                    // letting several registrations hash in parallel instead of queueing one after another.
                    return vertx.executeBlocking(() -> passwordHasher.hash(password), false);
                });
    }

    /**
     * Returns a signed JWT if the credentials are valid. After too many failures from the same IP for the same
     * email the answer is 429 (see LoginRateLimiter) - checked before BCrypt, so blocked attempts cost nothing.
     */
    public Future<String> login(LoginRequest request, String clientIp) {
        if (request == null || isBlank(request.email()) || isBlank(request.password())) {
            return Future.failedFuture(new BadRequestException("email and password are required"));
        }

        String email = normalizeEmail(request.email());
        return loginRateLimiter.blockedFor(clientIp, email)
                .compose(blockedFor -> {
                    if (blockedFor.isPresent()) {
                        return Future.failedFuture(new TooManyRequestsException(
                                "Too many failed login attempts. Try again later", blockedFor.getAsLong()));
                    }
                    return userRepository.findByEmail(email)
                            .compose(user -> vertx.executeBlocking(() -> checkPassword(user, request.password()), false))
                            .compose(user -> {
                                if (user.isEmpty()) {
                                    // Same message for unknown email and wrong password, so attackers can't discover accounts
                                    return loginRateLimiter.recordFailure(clientIp, email)
                                            .compose(v -> Future.failedFuture(new UnauthorizedException(INVALID_CREDENTIALS)));
                                }
                                if (!user.get().active()) {
                                    // Only said after a correct password, so it doesn't reveal anything to a guesser
                                    return Future.failedFuture(new UnauthorizedException("Account is disabled"));
                                }
                                return loginRateLimiter.reset(clientIp, email)
                                        .map(v -> jwtService.generateToken(user.get()));
                            });
                });
    }

    /**
     * The caller changes their own password (any role). The current password must be right. All the caller's
     * existing tokens stop working, including the one used for this request, so a new token is returned.
     */
    public Future<String> changePassword(AuthUser caller, ChangePasswordRequest request) {
        String validationError = passwordChangeError(request);
        if (validationError != null) {
            return Future.failedFuture(new BadRequestException(validationError));
        }

        return userRepository.findById(caller.id())
                .map(user -> user.orElseThrow(() -> new UnauthorizedException("Invalid or expired token")))
                .compose(user -> vertx.executeBlocking(() -> passwordHasher.matches(request.currentPassword(), user.passwordHash()), false))
                .compose(matches -> matches
                        ? vertx.executeBlocking(() -> passwordHasher.hash(request.newPassword()), false)
                        // 400, not 401: the caller is logged in, only the value they typed is wrong
                        : Future.failedFuture(new BadRequestException("Current password is incorrect")))
                .compose(hash -> userRepository.updatePassword(caller.id(), hash))
                .map(updated -> jwtService.generateToken(
                        updated.orElseThrow(() -> new UnauthorizedException("Invalid or expired token"))));
    }

    private static String passwordChangeError(ChangePasswordRequest request) {
        if (request == null) {
            return "Request body is required";
        }
        if (isBlank(request.currentPassword())) {
            return "currentPassword is required";
        }
        String newPasswordError = passwordError(request.newPassword(), "newPassword");
        if (newPasswordError != null) {
            return newPasswordError;
        }
        if (request.newPassword().equals(request.currentPassword())) {
            return "newPassword must be different from currentPassword";
        }
        return null;
    }

    private Optional<User> checkPassword(Optional<User> user, String password) {
        String hash = user.map(User::passwordHash).orElse(dummyHash);
        boolean matches = passwordHasher.matches(password, hash);
        return matches ? user : Optional.empty();
    }

    /** Returns an error message, or null if the request is valid. */
    private static String validateRegistration(RegisterRequest request) {
        if (request == null) {
            return "Request body is required";
        }
        if (isBlank(request.name())) {
            return "name is required";
        }
        if (request.name().trim().length() > MAX_NAME_LENGTH) {
            return "name must be at most " + MAX_NAME_LENGTH + " characters";
        }
        return credentialsError(request.email(), request.password());
    }

    /** Checks a new account's email and password. Returns an error message, or null if both are valid. */
    private static String credentialsError(String email, String password) {
        if (isBlank(email)) {
            return "email is required";
        }
        String trimmed = email.trim();
        if (trimmed.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(trimmed).matches()) {
            return "email is not valid";
        }
        return passwordError(password, "password");
    }

    /** The password rules for every new password; field is the name used in the message. */
    private static String passwordError(String password, String field) {
        if (isBlank(password)) {
            return field + " is required";
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            return field + " must be at least " + MIN_PASSWORD_LENGTH + " characters";
        }
        if (PasswordHasher.exceedsMaxLength(password)) {
            return field + " must be at most " + PasswordHasher.MAX_PASSWORD_BYTES + " characters";
        }
        return null;
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
