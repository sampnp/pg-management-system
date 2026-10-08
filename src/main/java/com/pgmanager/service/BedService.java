package com.pgmanager.service;

import com.pgmanager.dto.BedRequest;
import com.pgmanager.dto.BedStatusRequest;
import com.pgmanager.exception.BadRequestException;
import com.pgmanager.exception.ConflictException;
import com.pgmanager.exception.NotFoundException;
import com.pgmanager.model.Bed;
import com.pgmanager.model.BedStatus;
import com.pgmanager.model.Room;
import com.pgmanager.repository.BedRepository;
import com.pgmanager.repository.RoomRepository;
import com.pgmanager.repository.TenantBedHistoryRepository;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class BedService {

    static final String BED_NOT_FOUND = "Bed not found";
    private static final int MAX_BED_NUMBER_LENGTH = 20;

    private final Pool pool;
    private final RoomRepository roomRepository;
    private final BedRepository bedRepository;
    private final TenantBedHistoryRepository historyRepository;

    public BedService(Pool pool, RoomRepository roomRepository, BedRepository bedRepository,
                      TenantBedHistoryRepository historyRepository) {
        this.pool = pool;
        this.roomRepository = roomRepository;
        this.bedRepository = bedRepository;
        this.historyRepository = historyRepository;
    }

    public Future<Bed> create(UUID roomId, BedRequest request) {
        return Future.succeededFuture(request)
                .map(BedService::validate)
                .compose(valid -> requireRoom(roomId)
                        .compose(room -> bedRepository.countByRoomId(roomId)
                                .compose(bedCount -> {
                                    // A room's capacity is the maximum number of beds it can hold
                                    if (bedCount >= room.capacity()) {
                                        return Future.failedFuture(new ConflictException(
                                                "Room is full: it already has " + bedCount + " of " + room.capacity() + " beds"));
                                    }
                                    return bedRepository.create(roomId, valid.bedNumber());
                                })));
    }

    public Future<List<Bed>> listByRoom(UUID roomId) {
        return requireRoom(roomId)
                .compose(room -> bedRepository.findByRoomId(roomId));
    }

    public Future<Bed> findById(UUID id) {
        return bedRepository.findById(id)
                .map(bed -> bed.orElseThrow(() -> new NotFoundException(BED_NOT_FOUND)));
    }

    public Future<Bed> update(UUID id, BedRequest request) {
        return Future.succeededFuture(request)
                .map(BedService::validate)
                .compose(valid -> bedRepository.updateBedNumber(id, valid.bedNumber()))
                .map(updated -> updated.orElseThrow(() -> new NotFoundException(BED_NOT_FOUND)));
    }

    /**
     * Manual status change. Check-in/check-out own the status, so the requested status must match
     * real occupancy: a bed can't be OCCUPIED with no tenant, or AVAILABLE while a tenant is checked in.
     * (Setting AVAILABLE on a bed with no tenant still works, e.g. to fix a bed marked OCCUPIED by hand earlier.)
     */
    public Future<Bed> updateStatus(UUID id, BedStatusRequest request) {
        return Future.succeededFuture(request)
                .map(BedService::parseStatus)
                // Lock the bed so a check-in can't happen between our check and our update
                .compose(requested -> pool.withTransaction(tx -> bedRepository.findByIdForUpdate(tx, id)
                        .map(bed -> bed.orElseThrow(() -> new NotFoundException(BED_NOT_FOUND)))
                        .compose(bed -> historyRepository.findCurrentByBedId(tx, id))
                        .compose(currentStay -> {
                            if (requested == BedStatus.OCCUPIED && currentStay.isEmpty()) {
                                return Future.failedFuture(new ConflictException(
                                        "A bed can only become OCCUPIED by checking a tenant in"));
                            }
                            if (requested == BedStatus.AVAILABLE && currentStay.isPresent()) {
                                return Future.failedFuture(new ConflictException(
                                        "Bed has a checked-in tenant; check the tenant out instead"));
                            }
                            return bedRepository.updateStatus(tx, id, requested);
                        })
                        .map(updated -> updated.orElseThrow(() -> new NotFoundException(BED_NOT_FOUND)))));
    }

    public Future<Void> delete(UUID id) {
        return findById(id)
                .compose(bed -> {
                    if (bed.status() == BedStatus.OCCUPIED) {
                        return Future.failedFuture(new ConflictException("Cannot delete an occupied bed"));
                    }
                    return bedRepository.delete(id);
                })
                .map(deleted -> {
                    if (!deleted) {
                        throw new NotFoundException(BED_NOT_FOUND);
                    }
                    return null;
                });
    }

    private Future<Room> requireRoom(UUID roomId) {
        return roomRepository.findById(roomId)
                .map(room -> room.orElseThrow(() -> new NotFoundException(RoomService.ROOM_NOT_FOUND)));
    }

    private static BedRequest validate(BedRequest request) {
        Validation.requireBody(request);
        return new BedRequest(Validation.requireText(request.bedNumber(), "bedNumber", MAX_BED_NUMBER_LENGTH));
    }

    private static BedStatus parseStatus(BedStatusRequest request) {
        Validation.requireBody(request);
        if (request.status() == null || request.status().isBlank()) {
            throw new BadRequestException("status is required");
        }
        try {
            return BedStatus.valueOf(request.status().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("status must be AVAILABLE or OCCUPIED");
        }
    }
}
