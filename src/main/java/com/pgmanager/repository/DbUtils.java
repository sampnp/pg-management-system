package com.pgmanager.repository;

import com.pgmanager.dto.Page;
import com.pgmanager.dto.PageRequest;
import io.vertx.core.Future;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Small helpers shared by the repositories. */
final class DbUtils {

    // PostgreSQL error codes: https://www.postgresql.org/docs/current/errcodes-appendix.html
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

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

    /**
     * One page of a list: a COUNT(*) for the total, then the rows of the page (LIMIT/OFFSET).
     * countSql and selectSql must use the same WHERE clause and parameters; selectSql must end with an
     * ORDER BY that has a unique last column (e.g. id), so the order - and therefore each page - is stable.
     * Pages past the end are empty, without running the second query.
     */
    static <T> Future<Page<T>> page(Pool pool, String countSql, String selectSql, Tuple params,
                                    PageRequest request, Function<Row, T> mapper) {
        return pool.preparedQuery(countSql).execute(params)
                .compose(countRows -> {
                    long total = countRows.iterator().next().getLong(0);
                    if (request.offset() >= total) {
                        return Future.succeededFuture(Page.of(List.<T>of(), request, total));
                    }
                    Tuple pageParams = Tuple.tuple();
                    for (int i = 0; i < params.size(); i++) {
                        pageParams.addValue(params.getValue(i));
                    }
                    pageParams.addValue(request.size()).addValue(request.offset());
                    String limit = " LIMIT $" + (params.size() + 1) + " OFFSET $" + (params.size() + 2);
                    return pool.preparedQuery(selectSql + limit).execute(pageParams)
                            .map(rows -> Page.of(mapAll(rows, mapper), request, total));
                });
    }

    /** A UNIQUE constraint was violated, e.g. a duplicate email or room number. */
    static boolean isUniqueViolation(Throwable err) {
        return hasSqlState(err, UNIQUE_VIOLATION);
    }

    /** A FOREIGN KEY was violated: the parent row does not exist, or a child row still references it. */
    static boolean isForeignKeyViolation(Throwable err) {
        return hasSqlState(err, FOREIGN_KEY_VIOLATION);
    }

    /** Name of the constraint or unique index that was violated, or null. Useful when a table has several. */
    static String violatedConstraint(Throwable err) {
        return err instanceof PgException pgException ? pgException.getConstraint() : null;
    }

    private static boolean hasSqlState(Throwable err, String sqlState) {
        return err instanceof PgException pgException && sqlState.equals(pgException.getSqlState());
    }
}
