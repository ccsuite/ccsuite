package ccsuite.ccacc;

import ccsuite.base.model.CCAPICredential;
import ccsuite.oauthclient.InferenceProbeUsageClient;
import ccsuite.oauthswap.usage.UsageService;
import ccsuite.usagetracker.model.LastReadings.Reading;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** After a restart the usage source starts from the log: no probe goes out for an account read a few minutes ago, and one whose window has reset since is probed as usual. */
class UsageCacheTest {

    static OffsetDateTime in(Duration d) { return OffsetDateTime.ofInstant(Instant.now().plus(d), ZoneOffset.UTC); }

    static Reading reading(String id, Duration age, Duration reset5, Duration reset7) {
        return new Reading(id, Instant.now().minus(age), 10.0, 50.0, in(reset5), in(reset7));
    }

    static UsageService service() { return new UsageService(null, new InferenceProbeUsageClient(), null, null); }

    @Test void aReadingWhoseWindowsAreStillRunningIsSeededWithItsTime() {
        var usage = service();
        var logged = Map.of("A", reading("A", Duration.ofMinutes(3), Duration.ofHours(2), Duration.ofDays(2)));
        var n = UsageCache.seed(usage, List.of(new CCAPICredential().id("A").tier(5)), logged, Instant.now());
        assertEquals(1, n);
        assertEquals(10.0, usage.lastKnown("A").fiveHourPct());
        assertEquals(50.0, usage.lastKnown("A").sevenDayPct());
        assertNotNull(usage.lastKnown("A").sevenDayElapsedPct());
        assertSame(usage.lastKnown("A"), usage.accountUsagePeriodic("A", false), "3 minutes old: no probe, the seeded reading");
    }

    @Test void aReadingWhoseWindowHasResetSinceIsNotSeededSoItIsProbed() {
        var usage = service();
        var logged = Map.of("A", reading("A", Duration.ofHours(3), Duration.ofHours(-1), Duration.ofDays(2)));
        assertEquals(0, UsageCache.seed(usage, List.of(new CCAPICredential().id("A").tier(5)), logged, Instant.now()));
        assertNull(usage.lastKnown("A"));
    }

    @Test void freeAccountsAndAccountsWithoutALogLineAreLeftAlone() {
        var usage = service();
        var logged = Map.of("F", reading("F", Duration.ofMinutes(3), Duration.ofHours(2), Duration.ofDays(2)));
        var creds = List.of(new CCAPICredential().id("F").tier(0), new CCAPICredential().id("NEW").tier(5));
        assertEquals(0, UsageCache.seed(usage, creds, logged, Instant.now()));
        assertNull(usage.lastKnown("F"));
        assertNull(usage.lastKnown("NEW"));
    }
}
