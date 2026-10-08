package com.pgmanager.service;

import com.pgmanager.dto.Page;
import com.pgmanager.dto.PageRequest;
import com.pgmanager.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Reading ?page and ?size, and the numbers in a Page. */
class PaginationTest {

    @Test
    void defaultsAreFirstPageOfTwenty() {
        assertEquals(new PageRequest(0, 20), Validation.pageRequest(null, null));
        assertEquals(new PageRequest(0, 20), Validation.pageRequest(" ", ""));
    }

    @Test
    void customPageAndSize() {
        assertEquals(new PageRequest(3, 50), Validation.pageRequest("3", " 50 "));
        assertEquals(new PageRequest(0, 100), Validation.pageRequest("0", "100"));
        assertEquals(150, new PageRequest(3, 50).offset());
    }

    @ParameterizedTest
    @CsvSource({
            "-1, 20, page must be a number of 0 or more",
            "abc, 20, page must be a number of 0 or more",
            "1.5, 20, page must be a number of 0 or more",
            "99999999999, 20, page must be a number of 0 or more",
            "0, 0, size must be a number between 1 and 100",
            "0, 101, size must be a number between 1 and 100",
            "0, -5, size must be a number between 1 and 100",
            "0, ten, size must be a number between 1 and 100"})
    void invalidValuesAreRejected(String page, String size, String expectedMessage) {
        BadRequestException error = assertThrows(BadRequestException.class, () -> Validation.pageRequest(page, size));
        assertEquals(expectedMessage, error.getMessage());
    }

    @Test
    void largePageNumberDoesNotOverflowTheOffset() {
        assertEquals(2_000_000_000L * 100, new PageRequest(2_000_000_000, 100).offset());
    }

    @ParameterizedTest
    @CsvSource({"0, 20, 0", "1, 20, 1", "20, 20, 1", "21, 20, 2", "125, 20, 7", "100, 100, 1"})
    void totalPagesRoundsUp(long totalItems, int size, int expectedPages) {
        Page<String> page = Page.of(List.of(), new PageRequest(0, size), totalItems);
        assertEquals(expectedPages, page.totalPages());
        assertEquals(totalItems, page.totalItems());
    }
}
