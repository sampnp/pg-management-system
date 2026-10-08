package com.pgmanager.service;

import com.pgmanager.dto.PaymentRequest;
import com.pgmanager.exception.BadRequestException;
import com.pgmanager.exception.ConflictException;
import com.pgmanager.exception.NotFoundException;
import com.pgmanager.model.Payment;
import com.pgmanager.model.PaymentMethod;
import com.pgmanager.model.PaymentStatus;
import com.pgmanager.model.Tenant;
import com.pgmanager.model.TenantStatus;
import com.pgmanager.repository.PaymentRepository;
import com.pgmanager.repository.TenantRepository;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static com.pgmanager.TestFutures.await;
import static com.pgmanager.TestFutures.awaitFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private static final String TENANT_ID = UUID.randomUUID().toString();
    private static final BigDecimal AMOUNT = new BigDecimal("8000");
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final LocalDate PAID_ON = LocalDate.of(2026, 10, 9);

    private PaymentRepository paymentRepository;
    private TenantRepository tenantRepository;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        tenantRepository = mock(TenantRepository.class);
        paymentService = new PaymentService(paymentRepository, tenantRepository);
        // Echo back whatever the service asks the repository to save
        when(paymentRepository.create(any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> Future.succeededFuture(
                new Payment(UUID.randomUUID(), call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3),
                        call.getArgument(4), call.getArgument(5), call.getArgument(6), Instant.now(), Instant.now())));
    }

    // ---------- create ----------

    @Test
    void createNormalizesValuesBeforeSaving() throws Exception {
        tenantExists();

        Payment payment = await(paymentService.create(new PaymentRequest(
                TENANT_ID, AMOUNT, " 2026-10 ", "2026-10-09", "upi", "paid", "  UPI123456 ")));

        assertEquals(UUID.fromString(TENANT_ID), payment.tenantId());
        assertEquals(OCTOBER, payment.rentMonth());
        assertEquals(PAID_ON, payment.paymentDate());
        assertEquals(PaymentMethod.UPI, payment.paymentMethod());
        assertEquals(PaymentStatus.PAID, payment.status());
        assertEquals("UPI123456", payment.receiptId());
    }

    @ParameterizedTest
    @EnumSource(PaymentMethod.class)
    void everyPaymentMethodIsAccepted(PaymentMethod method) throws Exception {
        tenantExists();

        Payment payment = await(paymentService.create(paid(method.name())));

        assertEquals(method, payment.paymentMethod());
    }

    @Test
    void pendingPaymentNeedsNoDateOrMethod() throws Exception {
        tenantExists();

        Payment payment = await(paymentService.create(new PaymentRequest(TENANT_ID, AMOUNT, "2026-11", null, null, "PENDING", null)));

        assertEquals(PaymentStatus.PENDING, payment.status());
        assertEquals(null, payment.paymentDate());
        assertEquals(null, payment.paymentMethod());
    }

    @Test
    void partialAmountDifferentFromMonthlyRentIsAllowed() throws Exception {
        tenantExists();

        Payment payment = await(paymentService.create(new PaymentRequest(TENANT_ID, new BigDecimal("2500.50"), "2026-10",
                "2026-10-09", "CASH", "PAID", null)));

        assertEquals(new BigDecimal("2500.50"), payment.amount());
    }

    static Stream<Arguments> invalidPayments() {
        return Stream.of(
                Arguments.of(null, "Request body is required"),
                Arguments.of(request(null, AMOUNT, "2026-10", "2026-10-09", "UPI", "PAID"), "tenantId is required"),
                Arguments.of(request("not-a-uuid", AMOUNT, "2026-10", "2026-10-09", "UPI", "PAID"), "tenantId must be a valid UUID"),
                Arguments.of(request(TENANT_ID, null, "2026-10", "2026-10-09", "UPI", "PAID"), "amount is required"),
                Arguments.of(request(TENANT_ID, BigDecimal.ZERO, "2026-10", "2026-10-09", "UPI", "PAID"), "amount must be greater than 0"),
                Arguments.of(request(TENANT_ID, new BigDecimal("-500"), "2026-10", "2026-10-09", "UPI", "PAID"), "amount must be greater than 0"),
                Arguments.of(request(TENANT_ID, new BigDecimal("10.555"), "2026-10", "2026-10-09", "UPI", "PAID"), "amount can have at most 2 decimal places"),
                Arguments.of(request(TENANT_ID, AMOUNT, null, "2026-10-09", "UPI", "PAID"), "rentMonth is required"),
                Arguments.of(request(TENANT_ID, AMOUNT, "2026-13", "2026-10-09", "UPI", "PAID"), "rentMonth must be in YYYY-MM format"),
                Arguments.of(request(TENANT_ID, AMOUNT, "2026-1", "2026-10-09", "UPI", "PAID"), "rentMonth must be in YYYY-MM format"),
                Arguments.of(request(TENANT_ID, AMOUNT, "10-2026", "2026-10-09", "UPI", "PAID"), "rentMonth must be in YYYY-MM format"),
                Arguments.of(request(TENANT_ID, AMOUNT, "+10000-01", "2026-10-09", "UPI", "PAID"), "rentMonth must be in YYYY-MM format"),
                Arguments.of(request(TENANT_ID, AMOUNT, "2026-10", "2026-10-09", "UPI", null), "status is required"),
                Arguments.of(request(TENANT_ID, AMOUNT, "2026-10", "2026-10-09", "UPI", "DONE"), "status must be PAID or PENDING"),
                Arguments.of(request(TENANT_ID, AMOUNT, "2026-10", "2026-10-09", "CHEQUE", "PAID"), "paymentMethod must be UPI, CASH or BANK_TRANSFER"),
                Arguments.of(request(TENANT_ID, AMOUNT, "2026-10", "09-10-2026", "UPI", "PAID"), "paymentDate must be a valid date in YYYY-MM-DD format"),
                Arguments.of(request(TENANT_ID, AMOUNT, "2026-10", null, "UPI", "PAID"), "paymentDate is required for a PAID payment"),
                Arguments.of(request(TENANT_ID, AMOUNT, "2026-10", "2026-10-09", null, "PAID"), "paymentMethod is required for a PAID payment"),
                Arguments.of(new PaymentRequest(TENANT_ID, AMOUNT, "2026-10", "2026-10-09", "UPI", "PAID", "R".repeat(101)),
                        "receiptId must be at most 100 characters"));
    }

    @ParameterizedTest
    @MethodSource("invalidPayments")
    void invalidPaymentFailsWith400WithoutTouchingTheDatabase(PaymentRequest request, String expectedMessage) throws Exception {
        Throwable error = awaitFailure(paymentService.create(request));

        assertInstanceOf(BadRequestException.class, error);
        assertEquals(expectedMessage, error.getMessage());
        verifyNoInteractions(tenantRepository);
        verify(paymentRepository, never()).create(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void paymentForUnknownTenantFailsWith404() throws Exception {
        when(tenantRepository.findById(UUID.fromString(TENANT_ID))).thenReturn(Future.succeededFuture(Optional.empty()));

        Throwable error = awaitFailure(paymentService.create(paid("UPI")));

        assertInstanceOf(NotFoundException.class, error);
        assertEquals("Tenant not found", error.getMessage());
        verify(paymentRepository, never()).create(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void duplicateDetectedByTheDatabaseIsReturnedAs409() throws Exception {
        tenantExists();
        when(paymentRepository.create(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Future.failedFuture(new ConflictException("A payment with this receiptId already exists")));

        Throwable error = awaitFailure(paymentService.create(paid("UPI")));

        assertInstanceOf(ConflictException.class, error);
    }

    @Test
    void checkedOutTenantCanStillGetPayments() throws Exception {
        when(tenantRepository.findById(UUID.fromString(TENANT_ID)))
                .thenReturn(Future.succeededFuture(Optional.of(tenant(TenantStatus.CHECKED_OUT))));

        assertEquals(PaymentStatus.PAID, await(paymentService.create(paid("CASH"))).status());
    }

    // ---------- get & list ----------

    @Test
    void findByIdReturnsPayment() throws Exception {
        Payment payment = payment(PaymentStatus.PAID);
        when(paymentRepository.findById(payment.id())).thenReturn(Future.succeededFuture(Optional.of(payment)));

        assertEquals(payment, await(paymentService.findById(payment.id())));
    }

    @Test
    void findByIdOfMissingPaymentFailsWith404() throws Exception {
        UUID id = UUID.randomUUID();
        when(paymentRepository.findById(id)).thenReturn(Future.succeededFuture(Optional.empty()));

        assertEquals("Payment not found", awaitFailure(paymentService.findById(id)).getMessage());
    }

    @Test
    void listPassesParsedFiltersToRepository() throws Exception {
        List<Payment> payments = List.of(payment(PaymentStatus.PENDING));
        when(paymentRepository.find(UUID.fromString(TENANT_ID), PaymentStatus.PENDING, OCTOBER)).thenReturn(Future.succeededFuture(payments));

        assertEquals(payments, await(paymentService.list(TENANT_ID, "pending", "2026-10")));
    }

    @Test
    void listWithoutFiltersReturnsEverything() throws Exception {
        when(paymentRepository.find(null, null, null)).thenReturn(Future.succeededFuture(List.of()));

        assertTrue(await(paymentService.list(null, "", null)).isEmpty());
    }

    @Test
    void listWithInvalidFilterFailsWith400() throws Exception {
        assertEquals("status must be PAID or PENDING", awaitFailure(paymentService.list(null, "LATE", null)).getMessage());
        assertEquals("rentMonth must be in YYYY-MM format", awaitFailure(paymentService.list(null, null, "Oct-2026")).getMessage());
        assertEquals("tenantId must be a valid UUID", awaitFailure(paymentService.list("123", null, null)).getMessage());
        verifyNoInteractions(paymentRepository);
    }

    // ---------- update ----------

    @Test
    void updateCorrectsPaymentDetails() throws Exception {
        Payment existing = payment(PaymentStatus.PENDING);
        Payment corrected = payment(PaymentStatus.PAID);
        when(paymentRepository.findById(existing.id())).thenReturn(Future.succeededFuture(Optional.of(existing)));
        when(paymentRepository.update(existing.id(), new BigDecimal("8500"), OCTOBER, PAID_ON, PaymentMethod.BANK_TRANSFER,
                PaymentStatus.PAID, "NEFT42")).thenReturn(Future.succeededFuture(Optional.of(corrected)));

        Payment result = await(paymentService.update(existing.id(), new PaymentRequest(
                null, new BigDecimal("8500"), "2026-10", "2026-10-09", "BANK_TRANSFER", "PAID", "NEFT42")));

        assertEquals(corrected, result);
    }

    @Test
    void updateCannotMovePaymentToAnotherTenant() throws Exception {
        Payment existing = payment(PaymentStatus.PAID);
        when(paymentRepository.findById(existing.id())).thenReturn(Future.succeededFuture(Optional.of(existing)));

        Throwable error = awaitFailure(paymentService.update(existing.id(), paid("UPI", UUID.randomUUID().toString())));

        assertInstanceOf(BadRequestException.class, error);
        assertEquals("tenantId of a payment cannot be changed", error.getMessage());
        verify(paymentRepository, never()).update(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateOfMissingPaymentFailsWith404() throws Exception {
        UUID id = UUID.randomUUID();
        when(paymentRepository.findById(id)).thenReturn(Future.succeededFuture(Optional.empty()));

        assertInstanceOf(NotFoundException.class, awaitFailure(paymentService.update(id, paid("UPI"))));
    }

    @Test
    void updateWithInvalidDataFailsWith400() throws Exception {
        Throwable error = awaitFailure(paymentService.update(UUID.randomUUID(), request(null, BigDecimal.ZERO, "2026-10", null, null, "PENDING")));

        assertEquals("amount must be greater than 0", error.getMessage());
        verifyNoInteractions(paymentRepository);
    }

    // ---------- tenant history ----------

    @Test
    void tenantHistoryOfUnknownTenantFailsWith404() throws Exception {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Future.succeededFuture(Optional.empty()));

        assertInstanceOf(NotFoundException.class, awaitFailure(paymentService.tenantHistory(tenantId)));
    }

    @Test
    void tenantWithNoPaymentsGetsEmptyList() throws Exception {
        tenantExists();
        when(paymentRepository.find(UUID.fromString(TENANT_ID), null, null)).thenReturn(Future.succeededFuture(List.of()));

        assertTrue(await(paymentService.tenantHistory(UUID.fromString(TENANT_ID))).isEmpty());
    }

    // ---------- helpers ----------

    private void tenantExists() {
        when(tenantRepository.findById(UUID.fromString(TENANT_ID))).thenReturn(Future.succeededFuture(Optional.of(tenant(TenantStatus.ACTIVE))));
    }

    private static PaymentRequest paid(String method) {
        return paid(method, TENANT_ID);
    }

    private static PaymentRequest paid(String method, String tenantId) {
        return new PaymentRequest(tenantId, AMOUNT, "2026-10", "2026-10-09", method, "PAID", null);
    }

    private static PaymentRequest request(String tenantId, BigDecimal amount, String rentMonth, String paymentDate,
                                          String method, String status) {
        return new PaymentRequest(tenantId, amount, rentMonth, paymentDate, method, status, null);
    }

    private static Tenant tenant(TenantStatus status) {
        return new Tenant(UUID.fromString(TENANT_ID), "Sambit", "9876543210", null, LocalDate.of(2026, 10, 1),
                new BigDecimal("8000"), BigDecimal.ZERO, status, Instant.now());
    }

    private static Payment payment(PaymentStatus status) {
        return new Payment(UUID.randomUUID(), UUID.fromString(TENANT_ID), AMOUNT, OCTOBER,
                status == PaymentStatus.PAID ? PAID_ON : null, status == PaymentStatus.PAID ? PaymentMethod.UPI : null,
                status, null, Instant.now(), Instant.now());
    }
}
