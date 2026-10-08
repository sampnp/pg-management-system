package com.pgmanager.service;

import com.pgmanager.dto.TenantRequest;
import com.pgmanager.exception.BadRequestException;
import com.pgmanager.exception.ConflictException;
import com.pgmanager.exception.NotFoundException;
import com.pgmanager.model.Tenant;
import com.pgmanager.model.TenantStatus;
import com.pgmanager.repository.TenantRepository;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantServiceTest {

    private static final BigDecimal RENT = new BigDecimal("8500");
    private static final BigDecimal DEPOSIT = new BigDecimal("10000");
    private static final LocalDate JOINING_DATE = LocalDate.of(2026, 10, 10);

    private TenantRepository tenantRepository;
    private TenantService tenantService;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        tenantService = new TenantService(tenantRepository);
    }

    @Test
    void createNormalizesValuesBeforeSaving() throws Exception {
        Tenant saved = tenant(TenantStatus.PENDING);
        when(tenantRepository.create("Sambit Behera", "+918599800080", "sambit@example.com", JOINING_DATE, RENT, DEPOSIT))
                .thenReturn(Future.succeededFuture(saved));

        Tenant result = await(tenantService.create(new TenantRequest(
                "  Sambit Behera ", "+91 85998-00080", " Sambit@Example.COM ", "2026-10-10", RENT, DEPOSIT)));

        assertEquals(saved, result);
        verify(tenantRepository).create("Sambit Behera", "+918599800080", "sambit@example.com", JOINING_DATE, RENT, DEPOSIT);
    }

    @Test
    void emailIsOptional() throws Exception {
        when(tenantRepository.create("Sambit", "9876543210", null, JOINING_DATE, RENT, BigDecimal.ZERO))
                .thenReturn(Future.succeededFuture(tenant(TenantStatus.PENDING)));

        await(tenantService.create(new TenantRequest("Sambit", "9876543210", "  ", "2026-10-10", RENT, BigDecimal.ZERO)));

        verify(tenantRepository).create("Sambit", "9876543210", null, JOINING_DATE, RENT, BigDecimal.ZERO);
    }

    static Stream<Arguments> invalidTenants() {
        return Stream.of(
                Arguments.of(null, "Request body is required"),
                Arguments.of(request("  ", "9876543210", null, "2026-10-10", RENT, DEPOSIT), "name is required"),
                Arguments.of(request("x".repeat(101), "9876543210", null, "2026-10-10", RENT, DEPOSIT), "name must be at most 100 characters"),
                Arguments.of(request("Sambit", null, null, "2026-10-10", RENT, DEPOSIT), "phone is required"),
                Arguments.of(request("Sambit", "12345", null, "2026-10-10", RENT, DEPOSIT), "phone must be 7 to 15 digits, optionally starting with +"),
                Arguments.of(request("Sambit", "98765abc10", null, "2026-10-10", RENT, DEPOSIT), "phone must be 7 to 15 digits, optionally starting with +"),
                Arguments.of(request("Sambit", "9876543210", "not-an-email", "2026-10-10", RENT, DEPOSIT), "email is not valid"),
                Arguments.of(request("Sambit", "9876543210", null, null, RENT, DEPOSIT), "joiningDate is required"),
                Arguments.of(request("Sambit", "9876543210", null, "10-10-2026", RENT, DEPOSIT), "joiningDate must be a valid date in YYYY-MM-DD format"),
                Arguments.of(request("Sambit", "9876543210", null, "2026-02-30", RENT, DEPOSIT), "joiningDate must be a valid date in YYYY-MM-DD format"),
                Arguments.of(request("Sambit", "9876543210", null, "2026-10-10", null, DEPOSIT), "monthlyRent is required"),
                Arguments.of(request("Sambit", "9876543210", null, "2026-10-10", BigDecimal.ZERO, DEPOSIT), "monthlyRent must be greater than 0"),
                Arguments.of(request("Sambit", "9876543210", null, "2026-10-10", new BigDecimal("-100"), DEPOSIT), "monthlyRent must be greater than 0"),
                Arguments.of(request("Sambit", "9876543210", null, "2026-10-10", new BigDecimal("8500.555"), DEPOSIT), "monthlyRent can have at most 2 decimal places"),
                Arguments.of(request("Sambit", "9876543210", null, "2026-10-10", new BigDecimal("100000000"), DEPOSIT), "monthlyRent is too large"),
                Arguments.of(request("Sambit", "9876543210", null, "2026-10-10", RENT, null), "securityDeposit is required"),
                Arguments.of(request("Sambit", "9876543210", null, "2026-10-10", RENT, new BigDecimal("-1")), "securityDeposit cannot be negative"));
    }

    @ParameterizedTest
    @MethodSource("invalidTenants")
    void invalidTenantFailsWith400WithoutTouchingTheDatabase(TenantRequest request, String expectedMessage) throws Exception {
        Throwable error = awaitFailure(tenantService.create(request));

        assertInstanceOf(BadRequestException.class, error);
        assertEquals(expectedMessage, error.getMessage());
        verifyNoInteractions(tenantRepository);
    }

    @Test
    void findByIdOfMissingTenantFailsWith404() throws Exception {
        UUID id = UUID.randomUUID();
        when(tenantRepository.findById(id)).thenReturn(Future.succeededFuture(Optional.empty()));

        Throwable error = awaitFailure(tenantService.findById(id));

        assertInstanceOf(NotFoundException.class, error);
        assertEquals("Tenant not found", error.getMessage());
    }

    @Test
    void updateSavesNewDetails() throws Exception {
        Tenant updated = tenant(TenantStatus.ACTIVE);
        when(tenantRepository.update(updated.id(), "Sambit Behera", "9876543210", null, JOINING_DATE, new BigDecimal("9000"), DEPOSIT))
                .thenReturn(Future.succeededFuture(Optional.of(updated)));

        Tenant result = await(tenantService.update(updated.id(),
                request("Sambit Behera", "9876543210", null, "2026-10-10", new BigDecimal("9000"), DEPOSIT)));

        assertEquals(updated, result);
    }

    @Test
    void updateOfMissingTenantFailsWith404() throws Exception {
        UUID id = UUID.randomUUID();
        when(tenantRepository.update(any(), any(), any(), any(), any(), any(), any())).thenReturn(Future.succeededFuture(Optional.empty()));

        assertInstanceOf(NotFoundException.class,
                awaitFailure(tenantService.update(id, request("Sambit", "9876543210", null, "2026-10-10", RENT, DEPOSIT))));
    }

    @Test
    void deletePendingTenant() throws Exception {
        Tenant tenant = tenant(TenantStatus.PENDING);
        when(tenantRepository.findById(tenant.id())).thenReturn(Future.succeededFuture(Optional.of(tenant)));
        when(tenantRepository.delete(tenant.id())).thenReturn(Future.succeededFuture(true));

        await(tenantService.delete(tenant.id()));

        verify(tenantRepository).delete(tenant.id());
    }

    @Test
    void deleteCheckedInTenantFailsWith409() throws Exception {
        Tenant tenant = tenant(TenantStatus.ACTIVE);
        when(tenantRepository.findById(tenant.id())).thenReturn(Future.succeededFuture(Optional.of(tenant)));

        Throwable error = awaitFailure(tenantService.delete(tenant.id()));

        assertInstanceOf(ConflictException.class, error);
        verify(tenantRepository, never()).delete(any());
    }

    @Test
    void deleteTenantWithHistoryFailsWith409FromTheDatabase() throws Exception {
        Tenant tenant = tenant(TenantStatus.CHECKED_OUT);
        when(tenantRepository.findById(tenant.id())).thenReturn(Future.succeededFuture(Optional.of(tenant)));
        when(tenantRepository.delete(tenant.id())).thenReturn(Future.failedFuture(
                new ConflictException("Tenant cannot be deleted because they have occupancy or payment history")));

        assertInstanceOf(ConflictException.class, awaitFailure(tenantService.delete(tenant.id())));
    }

    @Test
    void deleteMissingTenantFailsWith404() throws Exception {
        UUID id = UUID.randomUUID();
        when(tenantRepository.findById(id)).thenReturn(Future.succeededFuture(Optional.empty()));

        assertInstanceOf(NotFoundException.class, awaitFailure(tenantService.delete(id)));
        verify(tenantRepository, never()).delete(any());
    }

    private static TenantRequest request(String name, String phone, String email, String joiningDate,
                                         BigDecimal rent, BigDecimal deposit) {
        return new TenantRequest(name, phone, email, joiningDate, rent, deposit);
    }

    private static Tenant tenant(TenantStatus status) {
        return new Tenant(UUID.randomUUID(), "Sambit Behera", "9876543210", null, JOINING_DATE, RENT, DEPOSIT, status, Instant.now());
    }
}
