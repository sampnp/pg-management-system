package com.pgmanager.service;

import com.pgmanager.dto.RoleRequest;
import com.pgmanager.exception.BadRequestException;
import com.pgmanager.exception.ForbiddenException;
import com.pgmanager.exception.NotFoundException;
import com.pgmanager.model.Role;
import com.pgmanager.model.User;
import com.pgmanager.repository.UserRepository;
import com.pgmanager.security.AuthUser;
import io.vertx.core.Future;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private final AuthUser admin = new AuthUser(UUID.randomUUID(), "admin@example.com", Role.ADMIN);
    private final UUID userId = UUID.randomUUID();
    private UserRepository userRepository;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userService = new UserService(userRepository);
    }

    @Test
    void adminCanPromoteAUser() throws Exception {
        User promoted = new User(userId, "Sambit", "sambit@example.com", "hash", Role.ADMIN, Instant.now());
        when(userRepository.updateRole(userId, Role.ADMIN)).thenReturn(Future.succeededFuture(Optional.of(promoted)));

        User result = await(userService.changeRole(admin, userId, new RoleRequest(" admin ")));

        assertEquals(Role.ADMIN, result.role());
        verify(userRepository).updateRole(userId, Role.ADMIN);
    }

    @Test
    void managerCannotChangeRolesEvenIfTheRouteCheckWasMissing() throws Exception {
        AuthUser manager = new AuthUser(UUID.randomUUID(), "manager@example.com", Role.MANAGER);

        Throwable error = awaitFailure(userService.changeRole(manager, userId, new RoleRequest("ADMIN")));

        assertInstanceOf(ForbiddenException.class, error);
        verifyNoInteractions(userRepository);
    }

    @Test
    void managerCannotPromoteThemselves() throws Exception {
        AuthUser manager = new AuthUser(UUID.randomUUID(), "manager@example.com", Role.MANAGER);

        assertInstanceOf(ForbiddenException.class, awaitFailure(userService.changeRole(manager, manager.id(), new RoleRequest("ADMIN"))));
        verifyNoInteractions(userRepository);
    }

    @Test
    void missingCallerIsRejected() throws Exception {
        assertInstanceOf(ForbiddenException.class, awaitFailure(userService.changeRole(null, userId, new RoleRequest("ADMIN"))));
        verifyNoInteractions(userRepository);
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of(null, "Request body is required"),
                Arguments.of(new RoleRequest(null), "role is required"),
                Arguments.of(new RoleRequest("  "), "role is required"),
                Arguments.of(new RoleRequest("OWNER"), "role must be ADMIN or MANAGER"));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidRoleFailsWith400(RoleRequest request, String expectedMessage) throws Exception {
        Throwable error = awaitFailure(userService.changeRole(admin, userId, request));

        assertInstanceOf(BadRequestException.class, error);
        assertEquals(expectedMessage, error.getMessage());
        verifyNoInteractions(userRepository);
    }

    @Test
    void adminCannotChangeTheirOwnRole() throws Exception {
        Throwable error = awaitFailure(userService.changeRole(admin, admin.id(), new RoleRequest("MANAGER")));

        assertInstanceOf(BadRequestException.class, error);
        assertEquals("You cannot change your own role", error.getMessage());
        verifyNoInteractions(userRepository);
    }

    @Test
    void unknownUserFailsWith404() throws Exception {
        when(userRepository.updateRole(userId, Role.ADMIN)).thenReturn(Future.succeededFuture(Optional.empty()));

        Throwable error = awaitFailure(userService.changeRole(admin, userId, new RoleRequest("ADMIN")));

        assertInstanceOf(NotFoundException.class, error);
        assertEquals("User not found", error.getMessage());
    }
}
