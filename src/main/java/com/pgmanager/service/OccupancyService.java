package com.pgmanager.service;

import com.pgmanager.dto.CheckInRequest;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Check-in and check-out. Each one changes three tables (history, beds, tenants), so each runs in ONE
 * PostgreSQL transaction via pool.withTransaction(): if any step fails, everything is rolled back.
 *
 * Concurrency: the tenant row and then the bed row are locked with SELECT ... FOR UPDATE. Two requests
 * for the same bed (or the same tenant) therefore run one after the other, and the second one sees the
 * first one's result. Locks are always taken in the same order (tenant, then bed) to avoid deadlocks.
 */
public class OccupancyService {

    private final Pool pool;
    private final TenantRepository tenantRepository;
    private final BedRepository bedRepository;
    private final TenantBedHistoryRepository historyRepository;

    public OccupancyService(Pool pool, TenantRepository tenantRepository, BedRepository bedRepository,
                            TenantBedHistoryRepository historyRepository) {
        this.pool = pool;
        this.tenantRepository = tenantRepository;
        this.bedRepository = bedRepository;
        this.historyRepository = historyRepository;
    }

    public Future<Occupancy> checkIn(UUID tenantId, CheckInRequest request) {
        return Future.succeededFuture(request)
                .map(OccupancyService::parseBedId)
                .compose(bedId -> pool.withTransaction(tx -> lockTenant(tx, tenantId)
                        .compose(tenant -> historyRepository.findCurrentByTenantId(tx, tenantId))
                        .compose(currentStay -> {
                            if (currentStay.isPresent()) {
                                return Future.failedFuture(new ConflictException("Tenant is already checked in to a bed"));
                            }
                            return lockBed(tx, bedId);
                        })
                        .compose(bed -> {
                            if (bed.status() == BedStatus.OCCUPIED) {
                                return Future.failedFuture(new ConflictException("Bed is already occupied"));
                            }
                            return historyRepository.insert(tx, tenantId, bedId);
                        })
                        .compose(stayId -> bedRepository.updateStatus(tx, bedId, BedStatus.OCCUPIED))
                        .compose(bed -> tenantRepository.updateStatus(tx, tenantId, TenantStatus.ACTIVE))
                        .compose(v -> historyRepository.findCurrentByTenantId(tx, tenantId))
                        .map(Optional::orElseThrow)));
    }

    public Future<Occupancy> checkOut(UUID tenantId) {
        return pool.withTransaction(tx -> lockTenant(tx, tenantId)
                .compose(tenant -> historyRepository.findCurrentByTenantId(tx, tenantId))
                .map(currentStay -> currentStay.orElseThrow(
                        () -> new ConflictException("Tenant is not checked in to any bed")))
                .compose(stay -> lockBed(tx, stay.bedId())
                        .compose(bed -> historyRepository.close(tx, stay.id()))
                        .compose(v -> bedRepository.updateStatus(tx, stay.bedId(), BedStatus.AVAILABLE))
                        .compose(bed -> tenantRepository.updateStatus(tx, tenantId, TenantStatus.CHECKED_OUT))
                        .compose(v -> historyRepository.findById(tx, stay.id())))
                .map(Optional::orElseThrow));
    }

    /** The tenant's current stay; 404 if the tenant is not checked in anywhere. */
    public Future<Occupancy> currentBed(UUID tenantId) {
        return requireTenant(tenantId)
                .compose(tenant -> historyRepository.findCurrentByTenantId(pool, tenantId))
                .map(stay -> stay.orElseThrow(() -> new NotFoundException("Tenant is not checked in to any bed")));
    }

    /** All stays of the tenant, newest first. */
    public Future<List<Occupancy>> history(UUID tenantId) {
        return requireTenant(tenantId)
                .compose(tenant -> historyRepository.findByTenantId(pool, tenantId));
    }

    private Future<Tenant> requireTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .map(tenant -> tenant.orElseThrow(() -> new NotFoundException(TenantService.TENANT_NOT_FOUND)));
    }

    private Future<Tenant> lockTenant(SqlConnection tx, UUID tenantId) {
        return tenantRepository.findByIdForUpdate(tx, tenantId)
                .map(tenant -> tenant.orElseThrow(() -> new NotFoundException(TenantService.TENANT_NOT_FOUND)));
    }

    private Future<Bed> lockBed(SqlConnection tx, UUID bedId) {
        return bedRepository.findByIdForUpdate(tx, bedId)
                .map(bed -> bed.orElseThrow(() -> new NotFoundException(BedService.BED_NOT_FOUND)));
    }

    private static UUID parseBedId(CheckInRequest request) {
        Validation.requireBody(request);
        return Validation.requireUuid(request.bedId(), "bedId");
    }
}
