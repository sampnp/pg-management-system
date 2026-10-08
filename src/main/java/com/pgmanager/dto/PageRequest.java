package com.pgmanager.dto;

/** Which page of a list to return: ?page=0&size=20. page starts at 0; size is 1 to MAX_SIZE. */
public record PageRequest(int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public static final PageRequest FIRST = new PageRequest(0, DEFAULT_SIZE);

    /** Number of rows to skip (SQL OFFSET). long, so a large page number can't overflow. */
    public long offset() {
        return (long) page * size;
    }
}
