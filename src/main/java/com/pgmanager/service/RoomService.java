package com.pgmanager.service;

import com.pgmanager.dto.RoomRequest;
import com.pgmanager.exception.BadRequestException;
import com.pgmanager.exception.ConflictException;
import com.pgmanager.exception.NotFoundException;
import com.pgmanager.model.Property;
import com.pgmanager.model.Room;
import com.pgmanager.repository.BedRepository;
import com.pgmanager.repository.DashboardCache;
import com.pgmanager.repository.PropertyRepository;
import com.pgmanager.repository.RoomRepository;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public class RoomService {

    static final String ROOM_NOT_FOUND = "Room not found";
    private static final int MAX_ROOM_NUMBER_LENGTH = 20;
    private static final int MAX_CAPACITY = 50;

    private final PropertyRepository propertyRepository;
    private final RoomRepository roomRepository;
    private final BedRepository bedRepository;
    private final DashboardCache dashboardCache;
    private final Pool pool;

    public RoomService(Pool pool, PropertyRepository propertyRepository, RoomRepository roomRepository,
                       BedRepository bedRepository, DashboardCache dashboardCache) {
        this.pool = pool;
        this.propertyRepository = propertyRepository;
        this.roomRepository = roomRepository;
        this.bedRepository = bedRepository;
        this.dashboardCache = dashboardCache;
    }

    public Future<Room> create(UUID propertyId, RoomRequest request) {
        return Future.succeededFuture(request)
                .map(RoomService::validate)
                // Never trust the id in the URL: the property must really exist (404 otherwise)
                .compose(valid -> requireProperty(propertyId)
                        .compose(property -> roomRepository.create(propertyId, valid.roomNumber(), valid.capacity())))
                .compose(saved -> dashboardCache.invalidate(Set.of(propertyId)).map(saved));
    }

    public Future<List<Room>> listByProperty(UUID propertyId) {
        return requireProperty(propertyId)
                .compose(property -> roomRepository.findByPropertyId(propertyId));
    }

    public Future<Room> findById(UUID id) {
        return roomRepository.findById(id)
                .map(room -> room.orElseThrow(() -> new NotFoundException(ROOM_NOT_FOUND)));
    }

    /**
     * Count and update run in one transaction with the room row locked (like BedService.create), so a bed
     * added at the same moment can't end up in a room whose capacity was just lowered below the bed count.
     */
    public Future<Room> update(UUID id, RoomRequest request) {
        return Future.succeededFuture(request)
                .map(RoomService::validate)
                .compose(valid -> pool.withTransaction(tx -> roomRepository.findByIdForUpdate(tx, id)
                        .map(room -> room.orElseThrow(() -> new NotFoundException(ROOM_NOT_FOUND)))
                        .compose(room -> bedRepository.countByRoomId(tx, id))
                        .compose(bedCount -> {
                            if (valid.capacity() < bedCount) {
                                return Future.failedFuture(new ConflictException(
                                        "Capacity cannot be less than the number of beds in the room (" + bedCount + ")"));
                            }
                            return roomRepository.update(tx, id, valid.roomNumber(), valid.capacity());
                        })))
                .map(updated -> updated.orElseThrow(() -> new NotFoundException(ROOM_NOT_FOUND)));
    }

    public Future<Void> delete(UUID id) {
        // Read the room first: after the delete we still need to know which property's dashboard changed
        return findById(id)
                .compose(room -> roomRepository.delete(id)
                        .map(deleted -> {
                            if (!deleted) {
                                throw new NotFoundException(ROOM_NOT_FOUND);
                            }
                            return room.propertyId();
                        }))
                .compose(propertyId -> dashboardCache.invalidate(Set.of(propertyId)));
    }

    private Future<Property> requireProperty(UUID propertyId) {
        return propertyRepository.findById(propertyId)
                .map(property -> property.orElseThrow(() -> new NotFoundException(PropertyService.PROPERTY_NOT_FOUND)));
    }

    private static RoomRequest validate(RoomRequest request) {
        Validation.requireBody(request);
        String roomNumber = Validation.requireText(request.roomNumber(), "roomNumber", MAX_ROOM_NUMBER_LENGTH);
        Integer capacity = request.capacity();
        if (capacity == null) {
            throw new BadRequestException("capacity is required");
        }
        if (capacity <= 0) {
            throw new BadRequestException("capacity must be greater than 0");
        }
        if (capacity > MAX_CAPACITY) {
            throw new BadRequestException("capacity must be at most " + MAX_CAPACITY);
        }
        return new RoomRequest(roomNumber, capacity);
    }
}
