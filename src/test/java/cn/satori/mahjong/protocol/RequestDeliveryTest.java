package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class RequestDeliveryTest {
    @Test
    void lostReceiptRecoversAutomatically() throws Exception {
        try (var delivery = new RequestDelivery()) {
            var attempts = new AtomicInteger();
            var result =
                    delivery.send(
                            () ->
                                    attempts.incrementAndGet() == 1
                                            ? CompletableFuture.failedFuture(
                                                    new IOException("lost receipt"))
                                            : CompletableFuture.completedFuture("accepted"));
            assertEquals("accepted", result.get(4, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void definitiveRejectionsAndInvalidSuccessfulResponsesAreNotRepeated() {
        try (var delivery = new RequestDelivery()) {
            for (int status : new int[] {200, 400, 401, 402, 403, 409, 422}) {
                var attempts = new AtomicInteger();
                var result =
                        delivery.send(
                                () -> {
                                    attempts.incrementAndGet();
                                    return CompletableFuture.failedFuture(
                                            new SatoriApi.ApiException(status, "rejected"));
                                });
                assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
                assertEquals(1, attempts.get());
            }
        }
    }

    @Test
    void closingCancelsAnInflightRequest() {
        var delivery = new RequestDelivery();
        var transport = new CompletableFuture<String>();
        var result = delivery.send(() -> transport);
        delivery.close();
        assertTrue(result.isCancelled());
        assertTrue(transport.isCancelled());
        assertTrue(delivery.send(() -> CompletableFuture.completedFuture("late")).isCancelled());
    }

    @Test
    void onlyTemporaryFailuresAreRetryable() {
        assertTrue(RequestDelivery.retryable(new SatoriApi.ApiException(429, "rate_limit")));
        assertTrue(
                RequestDelivery.retryable(
                        new SatoriApi.ApiException(503, "invalid_server_response")));
        assertFalse(RequestDelivery.retryable(new SatoriApi.ApiException(502, "protocol_error")));
        assertFalse(RequestDelivery.retryable(new CancellationException()));
    }
}
