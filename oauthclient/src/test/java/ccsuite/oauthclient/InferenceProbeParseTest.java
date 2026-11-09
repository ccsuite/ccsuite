package ccsuite.oauthclient;

import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A used-up account answers the probe with HTTP 429; the reading and the time it comes back are still in what it sends, and must not be thrown away. */
class InferenceProbeParseTest {

    static final long RESET_7D = Instant.parse("2026-11-07T08:00:00Z").getEpochSecond(), RESET_5H = Instant.parse("2026-11-07T08:10:00Z").getEpochSecond();

    static HttpHeaders headers(String... kv) {
        var m = new java.util.LinkedHashMap<String, List<String>>();
        for (var i = 0; i < kv.length; i += 2) m.put(kv[i], List.of(kv[i + 1]));
        return HttpHeaders.of(m, (k, v) -> true);
    }

    @Test void aSuccessfulProbeIsReadAsBefore() {
        var u = InferenceProbeUsageClient.parse(200, headers("anthropic-ratelimit-unified-5h-utilization", "0.1", "anthropic-ratelimit-unified-5h-reset", "" + RESET_5H,
            "anthropic-ratelimit-unified-7d-utilization", "0.5", "anthropic-ratelimit-unified-7d-reset", "" + RESET_7D));
        assertEquals(10.0, u.fiveHourPct(), 1e-9);
        assertEquals(50.0, u.sevenDayPct(), 1e-9);
        assertEquals("2026-11-07T08:00:00Z", u.sevenDayResetsAt());
    }

    @Test void a429ThatCarriesTheWindowFiguresIsAReading() {
        var u = InferenceProbeUsageClient.parse(429, headers("anthropic-ratelimit-unified-5h-utilization", "0.10", "anthropic-ratelimit-unified-5h-reset", "" + RESET_5H,
            "anthropic-ratelimit-unified-7d-utilization", "1.0", "anthropic-ratelimit-unified-7d-reset", "" + RESET_7D));
        assertNotNull(u, "an exhausted account is a reading, not a failure");
        assertEquals(100.0, u.sevenDayPct(), 1e-9);
        assertEquals("2026-11-07T08:00:00Z", u.sevenDayResetsAt());
    }

    @Test void a429WithOnlyTheRejectedClaimAndTheResetNamesTheUsedUpWindow() {
        var u = InferenceProbeUsageClient.parse(429, headers("anthropic-ratelimit-unified-status", "rejected", "anthropic-ratelimit-unified-representative-claim", "seven_day",
            "anthropic-ratelimit-unified-reset", "" + RESET_7D));
        assertEquals(100.0, u.sevenDayPct(), 1e-9);
        assertEquals("2026-11-07T08:00:00Z", u.sevenDayResetsAt());
        assertNull(u.fiveHourPct(), "the other window is not known and is not invented");
        var five = InferenceProbeUsageClient.parse(429, headers("anthropic-ratelimit-unified-status", "rejected", "anthropic-ratelimit-unified-representative-claim", "five_hour",
            "anthropic-ratelimit-unified-reset", "" + RESET_5H));
        assertEquals(100.0, five.fiveHourPct(), 1e-9);
        assertEquals("2026-11-07T08:10:00Z", five.fiveHourResetsAt());
    }

    @Test void a429WithNothingUsableIsNoReadingAndAnErrorIsNeverTakenForOne() {
        assertNull(InferenceProbeUsageClient.parse(429, headers("retry-after", "30")));
        assertNull(InferenceProbeUsageClient.parse(500, headers("anthropic-ratelimit-unified-status", "rejected")));
        assertNull(InferenceProbeUsageClient.parse(401, headers()));
    }
}
