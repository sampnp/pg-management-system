package com.pgmanager.service;

import com.pgmanager.dto.RoomRequest;
import com.pgmanager.exception.BadRequestException;
import com.pgmanager.exception.ConflictException;
import com.pgmanager.exception.NotFoundException;
import com.pgmanager.model.Property;
import com.pgmanager.model.Room;
import com.pgmanager.repository.BedRepository;
import com.pgmanager.repository.PropertyRepository;
import com.pgmanager.repository.RoomRepository;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static com.pgmanager.TestFutures.await;
import static com.pgmanager.TestFutures.awaitFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RoomServiceTest {

    private final UUID propertyId = UUID.randomUUID();

    private PropertyRepository propertyRepository;
    private RoomRepository roomRepository;
    private BedRepository bedRepository;
    private RoomService roomService;

    @BeforeEach
    void setUp() {
        propertyRepository = mock(PropertyRepository.class);
        roomRepository = mock(RoomRepository.class);
        bedRepository = mock(BedRepository.class);
        roomService = new RoomService(propertyRepository, roomRepository, bedRepository);
    }

    @Test
    void createRoomUnderExistingProperty() throws Exception {
        propertyExists(true);
        Room saved = new Room(UUID.randomUUID(), propertyId, "101", 3);
        when(roomRepository.create(propertyId, "101", 3)).thenReturn(Future.succeededFuture(saved));

        Room result = await(roomService.create(propertyId, new RoomRequest(" 101 ", 3)));

        assertEquals(saved, result);
    }

    @Test
    void createRoomForMissingPropertyFailsWith404AndInsertsNothing() throws Exception {
        propertyExists(false);

        Throwable error = awaitFailure(roomService.create(propertyId, new RoomRequest("101", 3)));

        assertInstanceOf(NotFoundException.class, error);
        assertEquals("Property not found", error.getMessage());
        verify(roomRepository, never()).create(any(), anyString(), anyInt());
    }

    static Stream<Arguments> invalidRooms() {
        return Stream.of(
                Arguments.of(null, "Request body is required"),
                Arguments.of(new RoomRequest(" ", 3), "roomNumber is required"),
                Arguments.of(new RoomRequest("101", null), "capacity is required"),
                Arguments.of(new RoomRequest("101", 0), "capacity must be greater than 0"),
                Arguments.of(new RoomRequest("101", -2), "capacity must be greater than 0"),
                Arguments.of(new RoomRequest("101", 51), "capacity must be at most 50"));
    }

    @ParameterizedTest
    @MethodSource("invalidRooms")
    void invalidRoomFailsWith400(RoomRequest request, String expectedMessage) throws Exception {
        Throwable error = awaitFailure(roomService.create(propertyId, request));

        assertInstanceOf(BadRequestException.class, error);
        assertEquals(expectedMessage, error.getMessage());
        verifyNoInteractions(propertyRepository, roomRepository);
    }

    @Test
    void listRoomsOfMissingPropertyFailsWith404() throws Exception {
        propertyExists(false);

        assertInstanceOf(NotFoundException.class, awaitFailure(roomService.listByProperty(propertyId)));
        verify(roomRepository, never()).findByPropertyId(any());
    }

    @Test
    void listRoomsOfExistingProperty() throws Exception {
        propertyExists(true);
        List<Room> rooms = List.of(new Room(UUID.randomUUID(), propertyId, "101", 2));
        when(roomRepository.findByPropertyId(propertyId)).thenReturn(Future.succeededFuture(rooms));

        assertEquals(rooms, await(roomService.listByProperty(propertyId)));
    }

    @Test
    void updateRoom() throws Exception {
        Room room = new Room(UUID.randomUUID(), propertyId, "101", 2);
        Room updated = new Room(room.id(), propertyId, "102", 4);
        when(roomRepository.findById(room.id())).thenReturn(Future.succeededFuture(Optional.of(room)));
        when(bedRepository.countByRoomId(room.id())).thenReturn(Future.succeededFuture(1));
        when(roomRepository.update(room.id(), "102", 4)).thenReturn(Future.succeededFuture(Optional.of(updated)));

        assertEquals(updated, await(roomService.update(room.id(), new RoomRequest("102", 4))));
    }

    @Test
    void updateCapacityBelowExistingBedCountFailsWith409() throws Exception {
        Room room = new Room(UUID.randomUUID(), propertyId, "101", 3);
        when(roomRepository.findById(room.id())).thenReturn(Future.succeededFuture(Optional.of(room)));
        when(bedRepository.countByRoomId(room.id())).thenReturn(Future.succeededFuture(3));

        Throwable error = awaitFailure(roomService.update(room.id(), new RoomRequest("101", 2)));

        assertInstanceOf(ConflictException.class, error);
        verify(roomRepository, never()).update(any(), anyString(), anyInt());
    }

    @Test
    void updateMissingRoomFailsWith404() throws Exception {
        UUID id = UUID.randomUUID();
        when(roomRepository.findById(id)).thenReturn(Future.succeededFuture(Optional.empty()));

        assertInstanceOf(NotFoundException.class, awaitFailure(roomService.update(id, new RoomRequest("101", 2))));
    }

    @Test
    void deleteMissingRoomFailsWith404() throws Exception {
        UUID id = UUID.randomUUID();
        when(roomRepository.delete(id)).thenReturn(Future.succeededFuture(false));

        assertInstanceOf(NotFoundException.class, awaitFailure(roomService.delete(id)));
    }

    private void propertyExists(boolean exists) {
        Optional<Property> property = exists
                ? Optional.of(new Property(propertyId, "Sunrise PG", "123 Main Road", "Hyderabad", Instant.now()))
                : Optional.empty();
        when(propertyRepository.findById(propertyId)).thenReturn(Future.succeededFuture(property));
    }
}
