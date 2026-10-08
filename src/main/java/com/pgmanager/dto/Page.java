package com.pgmanager.dto;

import java.util.List;

/**
 * One page of a list, with what a client needs to show page links. Returned by the list endpoints
 * that can grow without limit: properties, tenants, payments and maintenance issues.
 */
public record Page<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static <T> Page<T> of(List<T> items, PageRequest request, long totalItems) {
        int totalPages = (int) ((totalItems + request.size() - 1) / request.size());
        return new Page<>(items, request.page(), request.size(), totalItems, totalPages);
    }
}
