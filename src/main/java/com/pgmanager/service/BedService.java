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
import io.vertx.core.Future;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class BedService {

    static final String BED_NOT_FOUND = "Bed not found";
    private static final int MAX_BED_NUMBER_LENGTH = 20;

    private final RoomRepository roomRepository;
    private final BedRepository bedRepository;

    public BedService(RoomRepository roomRepository, BedRepository bedRepository) {
        this.roomRepository = roomRepository;
        this.bedRepository = bedRepository;
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

    /** Manual status change. From Phase 5, check-in/check-out will set the status automatically. */
    public Future<Bed> updateStatus(UUID id, BedStatusRequest request) {
        return Future.succeededFuture(request)
                .map(BedService::parseStatus)
                .compose(status -> bedRepository.updateStatus(id, status))
                .map(updated -> updated.orElseThrow(() -> new NotFoundException(BED_NOT_FOUND)));
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
