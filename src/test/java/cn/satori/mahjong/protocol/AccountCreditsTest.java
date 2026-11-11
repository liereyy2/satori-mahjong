package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class AccountCreditsTest {
    private final AtomicLong now = new AtomicLong(-10_000_000_000L);
    private final List<CompletableFuture<String>> requests = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    private final AccountCredits credits =
            new AccountCredits(
                    () -> {
                        var result = new CompletableFuture<String>();
                        requests.add(result);
                        return result;
                    },
                    Runnable::run,
                    now::get,
                    failures::add);

    @Test
    void controllersShareFiveSecondPollAndNeverOverlapSlowRequests() {
        credits.refresh();
        credits.refresh();
        assertEquals(1, requests.size());
        now.addAndGet(10_000_000_000L);
        credits.refresh();
        assertEquals(1, requests.size());
        requests.getFirst().complete("123456");
        assertEquals("123456", credits.value());
        credits.refresh();
        requests.get(1).complete("98765");
        now.addAndGet(4_999_999_999L);
        credits.refresh();
        assertEquals(2, requests.size());
        now.incrementAndGet();
        credits.refresh();
        assertEquals(3, requests.size());
        assertEquals("98765", credits.value());
    }

    @Test
    void failedReadClearsAmountAndNextScheduledReadRecovers() {
        credits.refresh();
        requests.getFirst().complete("123456");
        now.addAndGet(5_000_000_000L);
        credits.refresh();
        requests.get(1).completeExceptionally(new SatoriApi.ApiException(503, "http_503"));
        assertNull(credits.value());
        assertEquals("http_503", credits.error());
        assertEquals(1, failures.size());
        credits.refresh();
        assertEquals(2, requests.size());
        now.addAndGet(5_000_000_000L);
        credits.refresh();
        requests.get(2).complete("0");
        assertEquals("0", credits.value());
        assertEquals("", credits.error());
    }

    @Test
    void accountChangeDiscardsOldAmountAndOldAuthenticationFailure() {
        credits.refresh();
        var old = requests.getFirst();
        credits.reset();
        assertNull(credits.value());
        credits.refresh();
        old.completeExceptionally(new SatoriApi.ApiException(401, "http_401", true));
        assertTrue(failures.isEmpty());
        requests.get(1).complete("10001");
        credits.reset();
        assertNull(credits.value());
        credits.refresh();
        var pending = requests.get(2);
        credits.reset();
        pending.complete("9999999");
        assertNull(credits.value());
        assertEquals("", credits.error());
    }
}
