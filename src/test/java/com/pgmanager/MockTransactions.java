package com.pgmanager;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;

import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * For unit tests with a mocked Pool: makes pool.withTransaction(work) simply call work(connection).
 * Real commit/rollback is tested against PostgreSQL in the integration tests.
 */
public final class MockTransactions {

    private MockTransactions() {
    }

    @SuppressWarnings("unchecked")
    public static void runInline(Pool pool, SqlConnection connection) {
        when(pool.withTransaction(any())).thenAnswer(call -> {
            Function<SqlConnection, Future<Object>> work = call.getArgument(0);
            return work.apply(connection);
        });
    }
}
