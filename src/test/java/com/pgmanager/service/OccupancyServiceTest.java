package com.pgmanager.service;

import com.pgmanager.MockTransactions;
import com.pgmanager.dto.CheckInRequest;
import com.pgmanager.exception.BadRequestException;
import com.pgmanager.exception.ConflictException;
import com.pgmanager.exception.NotFoundException;
import com.pgmanager.model.Bed;
import com.pgmanager.model.BedStatus;
import com.pgmanager.model.Occupancy;
import com.pgmanager.model.Tenant;
import com.pgmanager.model.TenantStatus;
import com.pgmanager.repository.BedRepository;
import com.pgmanager.repository.TenantBedHistoryRepository;
import com.pgmanager.repository.TenantRepository;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.pgmanager.TestFutures.await;
import static com.pgmanager.TestFutures.awaitFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OccupancyServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID bedId = UUID.randomUUID();
    private final UUID roomId = UUID.randomUUID();
    private final SqlConnection tx = mock(SqlConnection.class);

    private Pool pool;
    private TenantRepository tenantRepository;
    private BedRepository bedRepository;
    private TenantBedHistoryRepository historyRepository;
    private OccupancyService occupancyService;

    @BeforeEach
    void setUp() {
        pool = mock(Pool.class);
        MockTransactions.runInline(pool, tx);
        tenantRepository = mock(TenantRepository.class);
        bedRepository = mock(BedRepository.class);
        historyRepository = mock(TenantBedHistoryRepository.class);
        occupancyService = new OccupancyService(pool, tenantRepository, bedRepository, historyRepository);

        // Default answers for the write steps; individual tests override the lookups
        when(historyRepository.insert(tx, tenantId, bedId)).thenReturn(Future.succeededFuture(UUID.randomUUID()));
        when(bedRepository.updateStatus(any(), any(), any())).thenReturn(Future.succeededFuture(Optional.of(bed(BedStatus.OCCUPIED))));
        when(tenantRepository.updateStatus(any(), any(), any())).thenReturn(Future.succeededFuture());
        when(historyRepository.close(any(), any())).thenReturn(Future.succeededFuture());
    }

    // ---------- check-in ----------

    @Test
    void checkInCreatesHistoryThenOccupiesBedThenActivatesTenant() throws Exception {
        tenantExists(TenantStatus.PENDING);
        Occupancy newStay = stay(null);
        when(historyRepository.findCurrentByTenantId(tx, tenantId))
                .thenReturn(Future.succeededFuture(Optional.empty()), Future.succeededFuture(Optional.of(newStay)));
        bedExists(BedStatus.AVAILABLE);

        Occupancy result = await(occupancyService.checkIn(tenantId, new CheckInRequest(bedId.toString())));

        assertEquals(newStay, result);
        InOrder order = inOrder(tenantRepository, bedRepository, historyRepository);
        order.verify(tenantRepository).findByIdForUpdate(tx, tenantId);
        order.verify(bedRepository).findByIdForUpdate(tx, bedId);
        order.verify(historyRepository).insert(tx, tenantId, bedId);
        order.verify(bedRepository).updateStatus(tx, bedId, BedStatus.OCCUPIED);
        order.verify(tenantRepository).updateStatus(tx, tenantId, TenantStatus.ACTIVE);
    }

    @Test
    void checkInAgainAfterCheckOutIsAllowed() throws Exception {
        tenantExists(TenantStatus.CHECKED_OUT);
        when(historyRepository.findCurrentByTenantId(tx, tenantId))
                .thenReturn(Future.succeededFuture(Optional.empty()), Future.succeededFuture(Optional.of(stay(null))));
        bedExists(BedStatus.AVAILABLE);

        await(occupancyService.checkIn(tenantId, new CheckInRequest(bedId.toString())));

        verify(historyRepository).insert(tx, tenantId, bedId);
    }

    @Test
    void checkInOfUnknownTenantFailsWith404AndWritesNothing() throws Exception {
        when(tenantRepository.findByIdForUpdate(tx, tenantId)).thenReturn(Future.succeededFuture(Optional.empty()));

        Throwable error = awaitFailure(occupancyService.checkIn(tenantId, new CheckInRequest(bedId.toString())));

        assertInstanceOf(NotFoundException.class, error);
        assertEquals("Tenant not found", error.getMessage());
        assertNothingWritten();
    }

    @Test
    void checkInToUnknownBedFailsWith404AndWritesNothing() throws Exception {
        tenantExists(TenantStatus.PENDING);
        when(historyRepository.findCurrentByTenantId(tx, tenantId)).thenReturn(Future.succeededFuture(Optional.empty()));
        when(bedRepository.findByIdForUpdate(tx, bedId)).thenReturn(Future.succeededFuture(Optional.empty()));

        Throwable error = awaitFailure(occupancyService.checkIn(tenantId, new CheckInRequest(bedId.toString())));

        assertInstanceOf(NotFoundException.class, error);
        assertEquals("Bed not found", error.getMessage());
        assertNothingWritten();
    }

    @Test
    void checkInToOccupiedBedFailsWith409AndWritesNothing() throws Exception {
        tenantExists(TenantStatus.PENDING);
        when(historyRepository.findCurrentByTenantId(tx, tenantId)).thenReturn(Future.succeededFuture(Optional.empty()));
        bedExists(BedStatus.OCCUPIED);

        Throwable error = awaitFailure(occupancyService.checkIn(tenantId, new CheckInRequest(bedId.toString())));

        assertInstanceOf(ConflictException.class, error);
        assertEquals("Bed is already occupied", error.getMessage());
        assertNothingWritten();
    }

    @Test
    void checkInOfTenantWhoAlreadyHasABedFailsWith409() throws Exception {
        tenantExists(TenantStatus.ACTIVE);
        when(historyRepository.findCurrentByTenantId(tx, tenantId)).thenReturn(Future.succeededFuture(Optional.of(stay(null))));

        Throwable error = awaitFailure(occupancyService.checkIn(tenantId, new CheckInRequest(bedId.toString())));

        assertInstanceOf(ConflictException.class, error);
        assertEquals("Tenant is already checked in to a bed", error.getMessage());
        verify(bedRepository, never()).findByIdForUpdate(any(), any());
        assertNothingWritten();
    }

    @Test
    void checkInWithMalformedBedIdFailsWith400BeforeAnyTransaction() throws Exception {
        Throwable error = awaitFailure(occupancyService.checkIn(tenantId, new CheckInRequest("not-a-uuid")));

        assertInstanceOf(BadRequestException.class, error);
        assertEquals("bedId must be a valid UUID", error.getMessage());
        verifyNoInteractions(pool, tenantRepository, bedRepository, historyRepository);
    }

    @Test
    void checkInWithoutBedIdFailsWith400() throws Exception {
        assertEquals("bedId is required", awaitFailure(occupancyService.checkIn(tenantId, new CheckInRequest(null))).getMessage());
    }

    // ---------- check-out ----------

    @Test
    void checkOutClosesStayThenFreesBedThenMarksTenantCheckedOut() throws Exception {
        tenantExists(TenantStatus.ACTIVE);
        Occupancy currentStay = stay(null);
        Occupancy closedStay = new Occupancy(currentStay.id(), tenantId, bedId, roomId, currentStay.propertyId(),
                currentStay.checkIn(), Instant.now());
        when(historyRepository.findCurrentByTenantId(tx, tenantId)).thenReturn(Future.succeededFuture(Optional.of(currentStay)));
        when(historyRepository.findById(tx, currentStay.id())).thenReturn(Future.succeededFuture(Optional.of(closedStay)));
        bedExists(BedStatus.OCCUPIED);

        Occupancy result = await(occupancyService.checkOut(tenantId));

        assertEquals(closedStay, result);
        InOrder order = inOrder(historyRepository, bedRepository, tenantRepository);
        order.verify(historyRepository).close(tx, currentStay.id());
        order.verify(bedRepository).updateStatus(tx, bedId, BedStatus.AVAILABLE);
        order.verify(tenantRepository).updateStatus(tx, tenantId, TenantStatus.CHECKED_OUT);
    }

    @Test
    void checkOutOfUnknownTenantFailsWith404() throws Exception {
        when(tenantRepository.findByIdForUpdate(tx, tenantId)).thenReturn(Future.succeededFuture(Optional.empty()));

        assertInstanceOf(NotFoundException.class, awaitFailure(occupancyService.checkOut(tenantId)));
        assertNothingWritten();
    }

    @Test
    void checkOutOfTenantWithoutBedFailsWith409() throws Exception {
        tenantExists(TenantStatus.CHECKED_OUT);
        when(historyRepository.findCurrentByTenantId(tx, tenantId)).thenReturn(Future.succeededFuture(Optional.empty()));

        Throwable error = awaitFailure(occupancyService.checkOut(tenantId));

        assertInstanceOf(ConflictException.class, error);
        assertEquals("Tenant is not checked in to any bed", error.getMessage());
        verify(historyRepository, never()).close(any(), any());
        assertNothingWritten();
    }

    // ---------- current bed & history ----------

    @Test
    void currentBedReturnsTheOpenStay() throws Exception {
        tenantFound();
        Occupancy currentStay = stay(null);
        when(historyRepository.findCurrentByTenantId(pool, tenantId)).thenReturn(Future.succeededFuture(Optional.of(currentStay)));

        assertEquals(currentStay, await(occupancyService.currentBed(tenantId)));
    }

    @Test
    void currentBedFailsWith404WhenTenantHasNoBed() throws Exception {
        tenantFound();
        when(historyRepository.findCurrentByTenantId(pool, tenantId)).thenReturn(Future.succeededFuture(Optional.empty()));

        Throwable error = awaitFailure(occupancyService.currentBed(tenantId));

        assertInstanceOf(NotFoundException.class, error);
        assertEquals("Tenant is not checked in to any bed", error.getMessage());
    }

    @Test
    void currentBedOfUnknownTenantFailsWith404() throws Exception {
        when(tenantRepository.findById(tenantId)).thenReturn(Future.succeededFuture(Optional.empty()));

        assertEquals("Tenant not found", awaitFailure(occupancyService.currentBed(tenantId)).getMessage());
    }

    @Test
    void historyReturnsAllStaysFromTheRepository() throws Exception {
        tenantFound();
        List<Occupancy> stays = List.of(stay(null), stay(Instant.now()));
        when(historyRepository.findByTenantId(pool, tenantId)).thenReturn(Future.succeededFuture(stays));

        assertEquals(stays, await(occupancyService.history(tenantId)));
    }

    // ---------- helpers ----------

    private void assertNothingWritten() {
        verify(historyRepository, never()).insert(any(), any(), any());
        verify(bedRepository, never()).updateStatus(any(), any(), any());
        verify(tenantRepository, never()).updateStatus(any(), any(), any());
    }

    private void tenantExists(TenantStatus status) {
        when(tenantRepository.findByIdForUpdate(tx, tenantId)).thenReturn(Future.succeededFuture(Optional.of(tenant(status))));
    }

    private void tenantFound() {
        when(tenantRepository.findById(tenantId)).thenReturn(Future.succeededFuture(Optional.of(tenant(TenantStatus.ACTIVE))));
    }

    private void bedExists(BedStatus status) {
        when(bedRepository.findByIdForUpdate(tx, bedId)).thenReturn(Future.succeededFuture(Optional.of(bed(status))));
    }

    private Tenant tenant(TenantStatus status) {
        return new Tenant(tenantId, "Sambit", "9876543210", null, LocalDate.of(2026, 10, 10),
                new BigDecimal("8500"), BigDecimal.ZERO, status, Instant.now());
    }

    private Bed bed(BedStatus status) {
        return new Bed(bedId, roomId, "A", status);
    }

    private Occupancy stay(Instant checkOut) {
        return new Occupancy(UUID.randomUUID(), tenantId, bedId, roomId, UUID.randomUUID(), Instant.now(), checkOut);
    }
}
