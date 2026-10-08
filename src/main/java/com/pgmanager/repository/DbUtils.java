package com.pgmanager.repository;

import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Small helpers shared by the repositories. */
final class DbUtils {

    private DbUtils() {
    }

    static Optional<Row> firstRow(RowSet<Row> rows) {
        var iterator = rows.iterator();
        return iterator.hasNext() ? Optional.of(iterator.next()) : Optional.empty();
    }

    static <T> List<T> mapAll(RowSet<Row> rows, Function<Row, T> mapper) {
        List<T> result = new ArrayList<>(rows.size());
        for (Row row : rows) {
            result.add(mapper.apply(row));
        }
        return result;
    }
}
