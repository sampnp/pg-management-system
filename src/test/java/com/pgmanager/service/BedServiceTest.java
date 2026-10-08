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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.pgmanager.TestFutures.await;
import static com.pgmanager.TestFutures.awaitFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BedServiceTest {

    private final Room room = new Room(UUID.randomUUID(), UUID.randomUUID(), "101", 2);

    private RoomRepository roomRepository;
    private BedRepository bedRepository;
    private BedService bedService;

    @BeforeEach
    void setUp() {
        roomRepository = mock(RoomRepository.class);
        bedRepository = mock(BedRepository.class);
        bedService = new BedService(roomRepository, bedRepository);
    }

    @Test
    void createBedInRoomWithFreeCapacity() throws Exception {
        roomExists(true);
        when(bedRepository.countByRoomId(room.id())).thenReturn(Future.succeededFuture(1));
        Bed saved = new Bed(UUID.randomUUID(), room.id(), "B", BedStatus.AVAILABLE);
        when(bedRepository.create(room.id(), "B")).thenReturn(Future.succeededFuture(saved));

        Bed result = await(bedService.create(room.id(), new BedRequest(" B ")));

        assertEquals(BedStatus.AVAILABLE, result.status());
        verify(bedRepository).create(room.id(), "B");
    }

    @Test
    void createBedInMissingRoomFailsWith404() throws Exception {
        roomExists(false);

        Throwable error = awaitFailure(bedService.create(room.id(), new BedRequest("A")));

        assertInstanceOf(NotFoundException.class, error);
        assertEquals("Room not found", error.getMessage());
        verify(bedRepository, never()).create(any(), anyString());
    }

    @Test
    void createBedInFullRoomFailsWith409() throws Exception {
        roomExists(true);
        when(bedRepository.countByRoomId(room.id())).thenReturn(Future.succeededFuture(2));

        Throwable error = awaitFailure(bedService.create(room.id(), new BedRequest("C")));

        assertInstanceOf(ConflictException.class, error);
        assertEquals("Room is full: it already has 2 of 2 beds", error.getMessage());
        verify(bedRepository, never()).create(any(), anyString());
    }

    @Test
    void createBedWithBlankNumberFailsWith400() throws Exception {
        Throwable error = awaitFailure(bedService.create(room.id(), new BedRequest("  ")));

        assertInstanceOf(BadRequestException.class, error);
        assertEquals("bedNumber is required", error.getMessage());
        verifyNoInteractions(roomRepository, bedRepository);
    }

    @Test
    void listBedsOfExistingRoom() throws Exception {
        roomExists(true);
        List<Bed> beds = List.of(new Bed(UUID.randomUUID(), room.id(), "A", BedStatus.AVAILABLE));
        when(bedRepository.findByRoomId(room.id())).thenReturn(Future.succeededFuture(beds));

        assertEquals(beds, await(bedService.listByRoom(room.id())));
    }

    @Test
    void updateBedNumber() throws Exception {
        UUID bedId = UUID.randomUUID();
        Bed updated = new Bed(bedId, room.id(), "Z", BedStatus.AVAILABLE);
        when(bedRepository.updateBedNumber(bedId, "Z")).thenReturn(Future.succeededFuture(Optional.of(updated)));

        assertEquals("Z", await(bedService.update(bedId, new BedRequest("Z"))).bedNumber());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OCCUPIED", "occupied", " Occupied "})
    void statusIsParsedCaseInsensitively(String status) throws Exception {
        UUID bedId = UUID.randomUUID();
        when(bedRepository.updateStatus(bedId, BedStatus.OCCUPIED))
                .thenReturn(Future.succeededFuture(Optional.of(new Bed(bedId, room.id(), "A", BedStatus.OCCUPIED))));

        Bed result = await(bedService.updateStatus(bedId, new BedStatusRequest(status)));

        assertEquals(BedStatus.OCCUPIED, result.status());
    }

    @Test
    void changeStatusBackToAvailable() throws Exception {
        UUID bedId = UUID.randomUUID();
        when(bedRepository.updateStatus(bedId, BedStatus.AVAILABLE))
                .thenReturn(Future.succeededFuture(Optional.of(new Bed(bedId, room.id(), "A", BedStatus.AVAILABLE))));

        assertEquals(BedStatus.AVAILABLE, await(bedService.updateStatus(bedId, new BedStatusRequest("AVAILABLE"))).status());
    }

    @Test
    void invalidStatusFailsWith400() throws Exception {
        Throwable error = awaitFailure(bedService.updateStatus(UUID.randomUUID(), new BedStatusRequest("BROKEN")));

        assertInstanceOf(BadRequestException.class, error);
        assertEquals("status must be AVAILABLE or OCCUPIED", error.getMessage());
        verifyNoInteractions(bedRepository);
    }

    @Test
    void statusChangeOfMissingBedFailsWith404() throws Exception {
        UUID bedId = UUID.randomUUID();
        when(bedRepository.updateStatus(bedId, BedStatus.OCCUPIED)).thenReturn(Future.succeededFuture(Optional.empty()));

        assertInstanceOf(NotFoundException.class, awaitFailure(bedService.updateStatus(bedId, new BedStatusRequest("OCCUPIED"))));
    }

    @Test
    void deletingOccupiedBedFailsWith409() throws Exception {
        UUID bedId = UUID.randomUUID();
        when(bedRepository.findById(bedId))
                .thenReturn(Future.succeededFuture(Optional.of(new Bed(bedId, room.id(), "A", BedStatus.OCCUPIED))));

        Throwable error = awaitFailure(bedService.delete(bedId));

        assertInstanceOf(ConflictException.class, error);
        verify(bedRepository, never()).delete(any());
    }

    @Test
    void deletingAvailableBedSucceeds() throws Exception {
        UUID bedId = UUID.randomUUID();
        when(bedRepository.findById(bedId))
                .thenReturn(Future.succeededFuture(Optional.of(new Bed(bedId, room.id(), "A", BedStatus.AVAILABLE))));
        when(bedRepository.delete(bedId)).thenReturn(Future.succeededFuture(true));

        await(bedService.delete(bedId));

        verify(bedRepository).delete(bedId);
    }

    private void roomExists(boolean exists) {
        when(roomRepository.findById(room.id())).thenReturn(Future.succeededFuture(exists ? Optional.of(room) : Optional.empty()));
    }
}
