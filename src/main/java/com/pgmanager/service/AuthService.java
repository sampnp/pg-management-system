package com.pgmanager.service;

import com.pgmanager.dto.LoginRequest;
import com.pgmanager.dto.RegisterRequest;
import com.pgmanager.exception.BadRequestException;
import com.pgmanager.exception.ConflictException;
import com.pgmanager.exception.UnauthorizedException;
import com.pgmanager.model.Role;
import com.pgmanager.model.User;
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
    private final PasswordHasher passwordHasher;
    private final JwtService jwtService;
    /** Checked when the email is unknown, so "unknown email" and "wrong password" take the same time. */
    private final String dummyHash;

    public AuthService(Vertx vertx, UserRepository userRepository, PasswordHasher passwordHasher, JwtService jwtService) {
        this.vertx = vertx;
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.jwtService = jwtService;
        this.dummyHash = passwordHasher.hash(UUID.randomUUID().toString());
    }

    public Future<User> register(RegisterRequest request) {
        String validationError = validateRegistration(request);
        if (validationError != null) {
            return Future.failedFuture(new BadRequestException(validationError));
        }

        String email = normalizeEmail(request.email());
        Role role = Role.valueOf(request.role().trim().toUpperCase(Locale.ROOT));

        return userRepository.findByEmail(email)
                .compose(existing -> {
                    if (existing.isPresent()) {
                        return Future.failedFuture(new ConflictException("Email already exists"));
                    }
                    // BCrypt is deliberately slow, so hash on a worker thread. 'false' = unordered,
                    // letting several registrations hash in parallel instead of queueing one after another.
                    return vertx.executeBlocking(() -> passwordHasher.hash(request.password()), false);
                })
                .compose(hash -> userRepository.insert(request.name().trim(), email, hash, role));
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
        if (isBlank(request.email())) {
            return "email is required";
        }
        String email = request.email().trim();
        if (email.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(email).matches()) {
            return "email is not valid";
        }
        if (isBlank(request.password())) {
            return "password is required";
        }
        if (request.password().length() < MIN_PASSWORD_LENGTH) {
            return "password must be at least " + MIN_PASSWORD_LENGTH + " characters";
        }
        if (PasswordHasher.exceedsMaxLength(request.password())) {
            return "password must be at most " + PasswordHasher.MAX_PASSWORD_BYTES + " characters";
        }
        if (isBlank(request.role())) {
            return "role is required";
        }
        if (!isValidRole(request.role())) {
            return "role must be ADMIN or MANAGER";
        }
        return null;
    }

    private static boolean isValidRole(String role) {
        try {
            Role.valueOf(role.trim().toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
