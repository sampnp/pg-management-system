package com.pgmanager.service;

import com.pgmanager.dto.Page;
import com.pgmanager.dto.PropertyRequest;
import com.pgmanager.exception.NotFoundException;
import com.pgmanager.model.Property;
import com.pgmanager.repository.DashboardCache;
import com.pgmanager.repository.PropertyRepository;
import io.vertx.core.Future;

import java.util.UUID;

public class PropertyService {

    static final String PROPERTY_NOT_FOUND = "Property not found";
    // Match the VARCHAR sizes in the database schema
    private static final int MAX_NAME_LENGTH = 150;
    private static final int MAX_ADDRESS_LENGTH = 500;
    private static final int MAX_CITY_LENGTH = 100;

    private final PropertyRepository propertyRepository;
    private final DashboardCache dashboardCache;

    public PropertyService(PropertyRepository propertyRepository, DashboardCache dashboardCache) {
        this.propertyRepository = propertyRepository;
        this.dashboardCache = dashboardCache;
    }

    public Future<Property> create(PropertyRequest request) {
        // If validate() throws, map() turns the exception into a failed Future (-> 400 response)
        return Future.succeededFuture(request)
                .map(PropertyService::validate)
                .compose(valid -> propertyRepository.create(valid.name(), valid.address(), valid.city()))
                .compose(saved -> dashboardCache.invalidate().map(saved));
    }

    /** ?page=0&size=20, newest first. An invalid page or size throws inside compose() -> 400. */
    public Future<Page<Property>> list(String page, String size) {
        return Future.succeededFuture()
                .compose(v -> propertyRepository.findPage(Validation.pageRequest(page, size)));
    }

    public Future<Property> findById(UUID id) {
        return propertyRepository.findById(id)
                .map(property -> property.orElseThrow(() -> new NotFoundException(PROPERTY_NOT_FOUND)));
    }

    public Future<Property> update(UUID id, PropertyRequest request) {
        return Future.succeededFuture(request)
                .map(PropertyService::validate)
                .compose(valid -> propertyRepository.update(id, valid.name(), valid.address(), valid.city()))
                .map(updated -> updated.orElseThrow(() -> new NotFoundException(PROPERTY_NOT_FOUND)));
    }

    public Future<Void> delete(UUID id) {
        return propertyRepository.delete(id)
                .map(deleted -> {
                    if (!deleted) {
                        throw new NotFoundException(PROPERTY_NOT_FOUND);
                    }
                    return null;
                })
                .compose(v -> dashboardCache.invalidate());
    }

    /** Returns a copy with trimmed values, or throws BadRequestException. */
    private static PropertyRequest validate(PropertyRequest request) {
        Validation.requireBody(request);
        return new PropertyRequest(
                Validation.requireText(request.name(), "name", MAX_NAME_LENGTH),
                Validation.requireText(request.address(), "address", MAX_ADDRESS_LENGTH),
                Validation.requireText(request.city(), "city", MAX_CITY_LENGTH));
    }
}
