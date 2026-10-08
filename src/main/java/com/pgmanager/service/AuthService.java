package com.pgmanager.service;

import com.pgmanager.dto.LoginRequest;
import com.pgmanager.dto.RegisterRequest;
import com.pgmanager.dto.TenantAccountRequest;
import com.pgmanager.exception.BadRequestException;
import com.pgmanager.exception.ConflictException;
import com.pgmanager.exception.NotFoundException;
import com.pgmanager.exception.UnauthorizedException;
import com.pgmanager.model.Role;
import com.pgmanager.model.User;
import com.pgmanager.repository.TenantRepository;
import com.pgmanager.repository.UserRepository;
import com.pgmanager.security.JwtService;
import com.pgmanager.security.PasswordHasher;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public class AuthService {

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
    /** Checked when the email is unknown, so "unknown email" and "wrong password" take the same time. */
    private final String dummyHash;

    public AuthService(Vertx vertx, UserRepository userRepository, TenantRepository tenantRepository,
                       PasswordHasher passwordHasher, JwtService jwtService) {
        this.vertx = vertx;
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.passwordHasher = passwordHasher;
        this.jwtService = jwtService;
        this.dummyHash = passwordHasher.hash(UUID.randomUUID().toString());
    }

    /**
     * Public registration always creates a MANAGER. The caller cannot choose a role, so nobody can
     * make themselves ADMIN here - an existing ADMIN has to promote them (see UserService.changeRole).
     */
    public Future<User> register(RegisterRequest request) {
        String validationError = validateRegistration(request);
        if (validationError != null) {
            return Future.failedFuture(new BadRequestException(validationError));
        }

        String email = normalizeEmail(request.email());
        return hashIfEmailIsFree(email, request.password())
                .compose(hash -> userRepository.insert(request.name().trim(), email, hash, Role.MANAGER));
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

    /** Returns a signed JWT if the credentials are valid. */
    public Future<String> login(LoginRequest request) {
        if (request == null || isBlank(request.email()) || isBlank(request.password())) {
            return Future.failedFuture(new BadRequestException("email and password are required"));
        }

        return userRepository.findByEmail(normalizeEmail(request.email()))
                .compose(user -> vertx.executeBlocking(() -> checkPassword(user, request.password()), false))
                .compose(user -> user.isPresent()
                        ? Future.succeededFuture(jwtService.generateToken(user.get()))
                        // Same message for unknown email and wrong password, so attackers can't discover accounts
                        : Future.failedFuture(new UnauthorizedException(INVALID_CREDENTIALS)));
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
        if (isBlank(password)) {
            return "password is required";
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            return "password must be at least " + MIN_PASSWORD_LENGTH + " characters";
        }
        if (PasswordHasher.exceedsMaxLength(password)) {
            return "password must be at most " + PasswordHasher.MAX_PASSWORD_BYTES + " characters";
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
