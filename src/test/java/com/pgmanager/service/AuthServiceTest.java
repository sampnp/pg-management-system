package com.pgmanager.service;

import com.pgmanager.config.JwtConfig;
import com.pgmanager.dto.LoginRequest;
import com.pgmanager.dto.RegisterRequest;
import com.pgmanager.exception.BadRequestException;
import com.pgmanager.exception.ConflictException;
import com.pgmanager.exception.UnauthorizedException;
import com.pgmanager.model.Role;
import com.pgmanager.model.User;
import com.pgmanager.repository.UserRepository;
import com.pgmanager.security.AuthUser;
import com.pgmanager.security.JwtService;
import com.pgmanager.security.PasswordHasher;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static com.pgmanager.TestFutures.await;
import static com.pgmanager.TestFutures.awaitFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** AuthService with a mocked repository: tests business rules without a database. */
class AuthServiceTest {

    private static Vertx vertx;

    // Low BCrypt cost keeps the tests fast
    private final PasswordHasher passwordHasher = new PasswordHasher(4);
    private UserRepository userRepository;
    private JwtService jwtService;
    private AuthService authService;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void closeVertx() throws Exception {
        await(vertx.close());
    }

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        jwtService = new JwtService(vertx, new JwtConfig("unit-test-secret-that-is-at-least-32-chars", 3600));
        authService = new AuthService(vertx, userRepository, passwordHasher, jwtService);
    }

    // ---------- registration ----------

    @Test
    void registerHashesPasswordAndNormalizesEmail() throws Exception {
        when(userRepository.findByEmail("sambit@example.com")).thenReturn(Future.succeededFuture(Optional.empty()));
        when(userRepository.insert(anyString(), anyString(), anyString(), any())).thenAnswer(call -> Future.succeededFuture(
                new User(UUID.randomUUID(), call.getArgument(0), call.getArgument(1), call.getArgument(2),
                        call.getArgument(3), Instant.now())));

        User user = await(authService.register(new RegisterRequest(" Sambit ", " Sambit@Example.com ", "password123", "manager")));

        assertEquals("Sambit", user.name());
        assertEquals("sambit@example.com", user.email());
        assertEquals(Role.MANAGER, user.role());
        assertNotEquals("password123", user.passwordHash());
        assertTrue(passwordHasher.matches("password123", user.passwordHash()));
    }

    @Test
    void registerWithExistingEmailFailsWithConflict() throws Exception {
        when(userRepository.findByEmail("sambit@example.com")).thenReturn(Future.succeededFuture(Optional.of(storedUser("password123"))));

        Throwable error = awaitFailure(authService.register(new RegisterRequest("Sambit", "sambit@example.com", "password123", "MANAGER")));

        assertInstanceOf(ConflictException.class, error);
        assertEquals("Email already exists", error.getMessage());
        verify(userRepository, never()).insert(any(), any(), any(), any());
    }

    static Stream<Arguments> invalidRegistrations() {
        return Stream.of(
                Arguments.of(null, "Request body is required"),
                Arguments.of(new RegisterRequest(null, "a@example.com", "password123", "MANAGER"), "name is required"),
                Arguments.of(new RegisterRequest("  ", "a@example.com", "password123", "MANAGER"), "name is required"),
                Arguments.of(new RegisterRequest("Sambit", null, "password123", "MANAGER"), "email is required"),
                Arguments.of(new RegisterRequest("Sambit", "not-an-email", "password123", "MANAGER"), "email is not valid"),
                Arguments.of(new RegisterRequest("Sambit", "a@example.com", null, "MANAGER"), "password is required"),
                Arguments.of(new RegisterRequest("Sambit", "a@example.com", "short", "MANAGER"), "password must be at least 8 characters"),
                Arguments.of(new RegisterRequest("Sambit", "a@example.com", "password123", null), "role is required"),
                Arguments.of(new RegisterRequest("Sambit", "a@example.com", "password123", "OWNER"), "role must be ADMIN or MANAGER"));
    }

    @ParameterizedTest
    @MethodSource("invalidRegistrations")
    void invalidRegistrationFailsWithBadRequestBeforeTouchingTheDatabase(RegisterRequest request, String expectedMessage) throws Exception {
        Throwable error = awaitFailure(authService.register(request));

        assertInstanceOf(BadRequestException.class, error);
        assertEquals(expectedMessage, error.getMessage());
        verifyNoInteractions(userRepository);
    }

    // ---------- login ----------

    @Test
    void loginWithCorrectPasswordReturnsTokenForThatUser() throws Exception {
        User stored = storedUser("password123");
        when(userRepository.findByEmail("sambit@example.com")).thenReturn(Future.succeededFuture(Optional.of(stored)));

        String token = await(authService.login(new LoginRequest("Sambit@example.com", "password123")));

        AuthUser authUser = await(jwtService.verify(token));
        assertEquals(stored.id(), authUser.id());
        assertEquals(stored.role(), authUser.role());
    }

    @Test
    void loginWithWrongPasswordFailsWithUnauthorized() throws Exception {
        when(userRepository.findByEmail("sambit@example.com")).thenReturn(Future.succeededFuture(Optional.of(storedUser("password123"))));

        Throwable error = awaitFailure(authService.login(new LoginRequest("sambit@example.com", "wrong-password")));

        assertInstanceOf(UnauthorizedException.class, error);
        assertEquals("Invalid email or password", error.getMessage());
    }

    @Test
    void loginWithUnknownEmailGivesTheSameErrorAsWrongPassword() throws Exception {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Future.succeededFuture(Optional.empty()));

        Throwable error = awaitFailure(authService.login(new LoginRequest("nobody@example.com", "password123")));

        assertInstanceOf(UnauthorizedException.class, error);
        assertEquals("Invalid email or password", error.getMessage());
    }

    @Test
    void loginWithMissingFieldsFailsWithBadRequest() throws Exception {
        Throwable error = awaitFailure(authService.login(new LoginRequest("sambit@example.com", "")));

        assertInstanceOf(BadRequestException.class, error);
        verifyNoInteractions(userRepository);
    }

    private User storedUser(String password) {
        return new User(UUID.randomUUID(), "Sambit", "sambit@example.com", passwordHasher.hash(password), Role.MANAGER, Instant.now());
    }
}
