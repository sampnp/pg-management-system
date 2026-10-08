package com.pgmanager;

import io.vertx.core.Future;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.fail;

/** Tests run on a plain JUnit thread, so they wait for Vert.x Futures with a timeout. */
public final class TestFutures {

    private static final long TIMEOUT_SECONDS = 30;

    private TestFutures() {
    }

    public static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Waits for the future and returns its failure; fails the test if it succeeded. */
    public static Throwable awaitFailure(Future<?> future) throws Exception {
        Throwable error = future.toCompletionStage().toCompletableFuture()
                .handle((value, err) -> err)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (error == null) {
            fail("Expected the future to fail, but it succeeded");
        }
        return error;
    }
}
