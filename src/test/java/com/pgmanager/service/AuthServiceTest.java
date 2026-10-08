package com.pgmanager.service;

import com.pgmanager.config.JwtConfig;
import com.pgmanager.dto.CreateUserRequest;
import com.pgmanager.dto.LoginRequest;
import com.pgmanager.dto.RegisterRequest;
import com.pgmanager.dto.TenantAccountRequest;
import com.pgmanager.exception.BadRequestException;
import com.pgmanager.exception.ConflictException;
import com.pgmanager.exception.ForbiddenException;
import com.pgmanager.exception.NotFoundException;
import com.pgmanager.exception.UnauthorizedException;
import com.pgmanager.model.Role;
import com.pgmanager.model.Tenant;
import com.pgmanager.model.TenantStatus;
import com.pgmanager.model.User;
import com.pgmanager.repository.TenantRepository;
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
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
import static org.mockito.ArgumentMatchers.eq;
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
    private TenantRepository tenantRepository;
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
        tenantRepository = mock(TenantRepository.class);
        jwtService = new JwtService(vertx, new JwtConfig("unit-test-secret-that-is-at-least-32-chars", 3600));
        authService = new AuthService(vertx, userRepository, tenantRepository, passwordHasher, jwtService, true);
    }

    // ---------- registration ----------

    @Test
    void registerHashesPasswordAndNormalizesEmail() throws Exception {
        when(userRepository.findByEmail("sambit@example.com")).thenReturn(Future.succeededFuture(Optional.empty()));
        when(userRepository.insert(anyString(), anyString(), anyString(), any())).thenAnswer(call -> Future.succeededFuture(
                new User(UUID.randomUUID(), call.getArgument(0), call.getArgument(1), call.getArgument(2),
                        call.getArgument(3), Instant.now())));

        User user = await(authService.register(new RegisterRequest(" Sambit ", " Sambit@Example.com ", "password123")));

        assertEquals("Sambit", user.name());
        assertEquals("sambit@example.com", user.email());
        assertEquals(Role.MANAGER, user.role());
        assertNotEquals("password123", user.passwordHash());
        assertTrue(passwordHasher.matches("password123", user.passwordHash()));
    }

    @Test
    void registrationAlwaysSavesAManagerNeverAnAdmin() throws Exception {
        when(userRepository.findByEmail("sambit@example.com")).thenReturn(Future.succeededFuture(Optional.empty()));
        when(userRepository.insert(anyString(), anyString(), anyString(), any())).thenAnswer(call -> Future.succeededFuture(
                new User(UUID.randomUUID(), call.getArgument(0), call.getArgument(1), call.getArgument(2),
                        call.getArgument(3), Instant.now())));

        await(authService.register(new RegisterRequest("Sambit", "sambit@example.com", "password123")));

        verify(userRepository).insert(eq("Sambit"), eq("sambit@example.com"), anyString(), eq(Role.MANAGER));
        verify(userRepository, never()).insert(any(), any(), any(), eq(Role.ADMIN));
    }

    @Test
    void tokenFromLoginCarriesTheRoleStoredInTheDatabase() throws Exception {
        User admin = new User(UUID.randomUUID(), "Admin", "admin@example.com", passwordHasher.hash("password123"), Role.ADMIN, Instant.now());
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Future.succeededFuture(Optional.of(admin)));

        String token = await(authService.login(new LoginRequest("admin@example.com", "password123")));

        assertEquals(Role.ADMIN, await(jwtService.verify(token)).role());
    }

    @Test
    void registerWithExistingEmailFailsWithConflict() throws Exception {
        when(userRepository.findByEmail("sambit@example.com")).thenReturn(Future.succeededFuture(Optional.of(storedUser("password123"))));

        Throwable error = awaitFailure(authService.register(new RegisterRequest("Sambit", "sambit@example.com", "password123")));

        assertInstanceOf(ConflictException.class, error);
        assertEquals("Email already exists", error.getMessage());
        verify(userRepository, never()).insert(any(), any(), any(), any());
    }

    static Stream<Arguments> invalidRegistrations() {
        return Stream.of(
                Arguments.of(null, "Request body is required"),
                Arguments.of(new RegisterRequest(null, "a@example.com", "password123"), "name is required"),
                Arguments.of(new RegisterRequest("  ", "a@example.com", "password123"), "name is required"),
                Arguments.of(new RegisterRequest("Sambit", null, "password123"), "email is required"),
                Arguments.of(new RegisterRequest("Sambit", "not-an-email", "password123"), "email is not valid"),
                Arguments.of(new RegisterRequest("Sambit", "a@example.com", null), "password is required"),
                Arguments.of(new RegisterRequest("Sambit", "a@example.com", "short"), "password must be at least 8 characters"));
    }

    @ParameterizedTest
    @MethodSource("invalidRegistrations")
    void invalidRegistrationFailsWithBadRequestBeforeTouchingTheDatabase(RegisterRequest request, String expectedMessage) throws Exception {
        Throwable error = awaitFailure(authService.register(request));

        assertInstanceOf(BadRequestException.class, error);
        assertEquals(expectedMessage, error.getMessage());
        verifyNoInteractions(userRepository);
    }

    @Test
    void registrationIsRefusedWhenPublicRegistrationIsOff() throws Exception {
        AuthService closed = new AuthService(vertx, userRepository, tenantRepository, passwordHasher, jwtService, false);

        Throwable error = awaitFailure(closed.register(new RegisterRequest("Sambit", "sambit@example.com", "password123")));

        assertInstanceOf(ForbiddenException.class, error);
        assertEquals("Public registration is disabled. Ask an administrator to create your account", error.getMessage());
        verifyNoInteractions(userRepository);
    }

    // ---------- staff accounts created by an ADMIN ----------

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "manager"})
    void adminCreatesStaffAccountWithTheChosenRole(String role) throws Exception {
        when(userRepository.findByEmail("meena@example.com")).thenReturn(Future.succeededFuture(Optional.empty()));
        when(userRepository.insert(anyString(), anyString(), anyString(), any())).thenAnswer(call -> Future.succeededFuture(
                new User(UUID.randomUUID(), call.getArgument(0), call.getArgument(1), call.getArgument(2),
                        call.getArgument(3), Instant.now())));

        User user = await(authService.createStaffAccount(new CreateUserRequest(" Meena ", "Meena@Example.com", "password123", role)));

        assertEquals("Meena", user.name());
        assertEquals("meena@example.com", user.email());
        assertEquals(Role.valueOf(role.toUpperCase()), user.role());
        assertTrue(passwordHasher.matches("password123", user.passwordHash()));
    }

    static Stream<Arguments> invalidStaffAccounts() {
        return Stream.of(
                Arguments.of(null, "Request body is required"),
                Arguments.of(new CreateUserRequest(" ", "a@example.com", "password123", "MANAGER"), "name is required"),
                Arguments.of(new CreateUserRequest("Meena", "not-an-email", "password123", "MANAGER"), "email is not valid"),
                Arguments.of(new CreateUserRequest("Meena", "a@example.com", "short", "MANAGER"), "password must be at least 8 characters"),
                Arguments.of(new CreateUserRequest("Meena", "a@example.com", "password123", null), "role is required"),
                Arguments.of(new CreateUserRequest("Meena", "a@example.com", "password123", "TENANT"), "role must be ADMIN or MANAGER"));
    }

    @ParameterizedTest
    @MethodSource("invalidStaffAccounts")
    void invalidStaffAccountFailsWith400(CreateUserRequest request, String expectedMessage) throws Exception {
        Throwable error = awaitFailure(authService.createStaffAccount(request));

        assertInstanceOf(BadRequestException.class, error);
        assertEquals(expectedMessage, error.getMessage());
        verifyNoInteractions(userRepository);
    }

    // ---------- first admin from BOOTSTRAP_ADMIN_* ----------

    @Test
    void firstAdminIsCreatedWhenThereIsNone() throws Exception {
        when(userRepository.adminExists()).thenReturn(Future.succeededFuture(false));
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Future.succeededFuture(Optional.empty()));
        when(userRepository.insert(anyString(), anyString(), anyString(), any())).thenAnswer(call -> Future.succeededFuture(
                new User(UUID.randomUUID(), call.getArgument(0), call.getArgument(1), call.getArgument(2),
                        call.getArgument(3), Instant.now())));

        await(authService.createFirstAdmin(" Owner@Example.com ", "owner-password"));

        verify(userRepository).insert(eq("Administrator"), eq("owner@example.com"), anyString(), eq(Role.ADMIN));
    }

    @Test
    void firstAdminIsSkippedOnceAnAdminExists() throws Exception {
        when(userRepository.adminExists()).thenReturn(Future.succeededFuture(true));

        await(authService.createFirstAdmin("owner@example.com", "owner-password"));

        verify(userRepository, never()).insert(any(), any(), any(), any());
    }

    @Test
    void firstAdminNeverTakesOverAnExistingAccount() throws Exception {
        when(userRepository.adminExists()).thenReturn(Future.succeededFuture(false));
        when(userRepository.findByEmail("sambit@example.com")).thenReturn(Future.succeededFuture(Optional.of(storedUser("password123"))));

        await(authService.createFirstAdmin("sambit@example.com", "owner-password"));

        verify(userRepository, never()).insert(any(), any(), any(), any());
        verify(userRepository, never()).updateRole(any(), any());
    }

    @Test
    void invalidBootstrapPasswordStopsTheStartup() throws Exception {
        Throwable error = awaitFailure(authService.createFirstAdmin("owner@example.com", "short"));

        assertInstanceOf(IllegalStateException.class, error);
        assertEquals("Invalid BOOTSTRAP_ADMIN settings: password must be at least 8 characters", error.getMessage());
        verifyNoInteractions(userRepository);
    }

    // ---------- tenant accounts ----------

    @Test
    void tenantAccountIsLinkedToTheTenantAndUsesTheirName() throws Exception {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Future.succeededFuture(Optional.of(tenant(tenantId))));
        when(userRepository.findByEmail("ravi@example.com")).thenReturn(Future.succeededFuture(Optional.empty()));
        when(userRepository.insertTenantUser(anyString(), anyString(), anyString(), any())).thenAnswer(call -> Future.succeededFuture(
                new User(UUID.randomUUID(), call.getArgument(0), call.getArgument(1), call.getArgument(2), Role.TENANT,
                        Instant.now(), call.getArgument(3))));

        User user = await(authService.createTenantAccount(tenantId, new TenantAccountRequest(" Ravi@Example.com ", "password123")));

        assertEquals("Ravi Kumar", user.name());
        assertEquals("ravi@example.com", user.email());
        assertEquals(Role.TENANT, user.role());
        assertEquals(tenantId, user.tenantId());
        assertTrue(passwordHasher.matches("password123", user.passwordHash()));
        verify(userRepository, never()).insert(any(), any(), any(), any());
    }

    @Test
    void tenantAccountForUnknownTenantFailsWith404() throws Exception {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Future.succeededFuture(Optional.empty()));

        Throwable error = awaitFailure(authService.createTenantAccount(tenantId, new TenantAccountRequest("ravi@example.com", "password123")));

        assertInstanceOf(NotFoundException.class, error);
        verify(userRepository, never()).insertTenantUser(any(), any(), any(), any());
    }

    @Test
    void tenantAccountWithTakenEmailFailsWith409() throws Exception {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Future.succeededFuture(Optional.of(tenant(tenantId))));
        when(userRepository.findByEmail("sambit@example.com")).thenReturn(Future.succeededFuture(Optional.of(storedUser("password123"))));

        Throwable error = awaitFailure(authService.createTenantAccount(tenantId, new TenantAccountRequest("sambit@example.com", "password123")));

        assertInstanceOf(ConflictException.class, error);
        verify(userRepository, never()).insertTenantUser(any(), any(), any(), any());
    }

    static Stream<Arguments> invalidTenantAccounts() {
        return Stream.of(
                Arguments.of(null, "Request body is required"),
                Arguments.of(new TenantAccountRequest(null, "password123"), "email is required"),
                Arguments.of(new TenantAccountRequest("not-an-email", "password123"), "email is not valid"),
                Arguments.of(new TenantAccountRequest("ravi@example.com", "short"), "password must be at least 8 characters"));
    }

    @ParameterizedTest
    @MethodSource("invalidTenantAccounts")
    void invalidTenantAccountFailsWith400(TenantAccountRequest request, String expectedMessage) throws Exception {
        Throwable error = awaitFailure(authService.createTenantAccount(UUID.randomUUID(), request));

        assertInstanceOf(BadRequestException.class, error);
        assertEquals(expectedMessage, error.getMessage());
        verifyNoInteractions(userRepository, tenantRepository);
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
    void switchedOffAccountCannotLogIn() throws Exception {
        User stored = storedUser("password123");
        User switchedOff = new User(stored.id(), stored.name(), stored.email(), stored.passwordHash(), Role.MANAGER,
                stored.createdAt(), null, false, 0);
        when(userRepository.findByEmail("sambit@example.com")).thenReturn(Future.succeededFuture(Optional.of(switchedOff)));

        Throwable error = awaitFailure(authService.login(new LoginRequest("sambit@example.com", "password123")));
        assertInstanceOf(UnauthorizedException.class, error);
        assertEquals("Account is disabled", error.getMessage());

        // With a wrong password it gives nothing away: same answer as for any wrong password
        Throwable wrongPassword = awaitFailure(authService.login(new LoginRequest("sambit@example.com", "wrong-password")));
        assertEquals("Invalid email or password", wrongPassword.getMessage());
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

    private static Tenant tenant(UUID id) {
        return new Tenant(id, "Ravi Kumar", "9876543210", null, LocalDate.of(2026, 10, 1), new BigDecimal("8500"),
                BigDecimal.ZERO, TenantStatus.ACTIVE, Instant.now());
    }

    private User storedUser(String password) {
        return new User(UUID.randomUUID(), "Sambit", "sambit@example.com", passwordHasher.hash(password), Role.MANAGER, Instant.now());
    }
}
