package com.pgmanager.service;

import com.pgmanager.dto.PropertyRequest;
import com.pgmanager.exception.BadRequestException;
import com.pgmanager.exception.NotFoundException;
import com.pgmanager.model.Property;
import com.pgmanager.repository.DashboardCache;
import com.pgmanager.repository.PropertyRepository;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PropertyServiceTest {

    private PropertyRepository propertyRepository;
    private PropertyService propertyService;

    private DashboardCache dashboardCache;

    @BeforeEach
    void setUp() {
        propertyRepository = mock(PropertyRepository.class);
        dashboardCache = mock(DashboardCache.class);
        when(dashboardCache.invalidate()).thenReturn(Future.succeededFuture());
        propertyService = new PropertyService(propertyRepository, dashboardCache);
    }

    @Test
    void createTrimsValuesBeforeSaving() throws Exception {
        Property saved = property("Sunrise PG");
        when(propertyRepository.create("Sunrise PG", "123 Main Road", "Hyderabad")).thenReturn(Future.succeededFuture(saved));

        Property result = await(propertyService.create(new PropertyRequest("  Sunrise PG ", " 123 Main Road", "Hyderabad  ")));

        assertEquals(saved, result);
        verify(propertyRepository).create("Sunrise PG", "123 Main Road", "Hyderabad");
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of(null, "Request body is required"),
                Arguments.of(new PropertyRequest(null, "addr", "city"), "name is required"),
                Arguments.of(new PropertyRequest("  ", "addr", "city"), "name is required"),
                Arguments.of(new PropertyRequest("PG", "", "city"), "address is required"),
                Arguments.of(new PropertyRequest("PG", "addr", null), "city is required"),
                Arguments.of(new PropertyRequest("x".repeat(151), "addr", "city"), "name must be at most 150 characters"));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidCreateFailsWith400WithoutTouchingTheDatabase(PropertyRequest request, String expectedMessage) throws Exception {
        Throwable error = awaitFailure(propertyService.create(request));

        assertInstanceOf(BadRequestException.class, error);
        assertEquals(expectedMessage, error.getMessage());
        verifyNoInteractions(propertyRepository);
    }

    @Test
    void findAllReturnsRepositoryResult() throws Exception {
        List<Property> properties = List.of(property("A"), property("B"));
        when(propertyRepository.findAll()).thenReturn(Future.succeededFuture(properties));

        assertEquals(properties, await(propertyService.findAll()));
    }

    @Test
    void findByIdReturnsProperty() throws Exception {
        Property property = property("Sunrise PG");
        when(propertyRepository.findById(property.id())).thenReturn(Future.succeededFuture(Optional.of(property)));

        assertEquals(property, await(propertyService.findById(property.id())));
    }

    @Test
    void findByIdOfMissingPropertyFailsWith404() throws Exception {
        UUID id = UUID.randomUUID();
        when(propertyRepository.findById(id)).thenReturn(Future.succeededFuture(Optional.empty()));

        Throwable error = awaitFailure(propertyService.findById(id));

        assertInstanceOf(NotFoundException.class, error);
        assertEquals("Property not found", error.getMessage());
    }

    @Test
    void updateReturnsUpdatedProperty() throws Exception {
        Property updated = property("New Name");
        when(propertyRepository.update(updated.id(), "New Name", "123 Main Road", "Hyderabad"))
                .thenReturn(Future.succeededFuture(Optional.of(updated)));

        Property result = await(propertyService.update(updated.id(), new PropertyRequest("New Name", "123 Main Road", "Hyderabad")));

        assertEquals("New Name", result.name());
    }

    @Test
    void updateOfMissingPropertyFailsWith404() throws Exception {
        UUID id = UUID.randomUUID();
        when(propertyRepository.update(id, "PG", "addr", "city")).thenReturn(Future.succeededFuture(Optional.empty()));

        assertInstanceOf(NotFoundException.class, awaitFailure(propertyService.update(id, new PropertyRequest("PG", "addr", "city"))));
    }

    @Test
    void deleteSucceedsWhenRowWasDeleted() throws Exception {
        UUID id = UUID.randomUUID();
        when(propertyRepository.delete(id)).thenReturn(Future.succeededFuture(true));

        await(propertyService.delete(id));

        verify(propertyRepository).delete(id);
    }

    @Test
    void deleteOfMissingPropertyFailsWith404() throws Exception {
        UUID id = UUID.randomUUID();
        when(propertyRepository.delete(id)).thenReturn(Future.succeededFuture(false));

        assertInstanceOf(NotFoundException.class, awaitFailure(propertyService.delete(id)));
    }

    private static Property property(String name) {
        return new Property(UUID.randomUUID(), name, "123 Main Road", "Hyderabad", Instant.now());
    }
}
