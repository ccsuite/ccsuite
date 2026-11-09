package ccsuite.oauthswap.usage;

import ccsuite.oauthclient.AccountUsage;
import ccsuite.oauthclient.InferenceProbeUsageClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A restart must not probe every account again at once: a reading made a few minutes before is the last known one, and its time is the cooldown clock. */
class UsageServiceSeedTest {

    static UsageService service() { return new UsageService(null, new InferenceProbeUsageClient(), null, null); }

    static final AccountUsage READING = new AccountUsage(10.0, "2026-11-07T20:00:00Z", 50.0, "2026-11-09T20:00:00Z", 40.0);

    @Test void aSeededReadingWithinTheCooldownIsReturnedWithoutAnyProbe() {
        var s = service();
        s.seed("A", READING, System.currentTimeMillis() - 2 * 60_000);
        assertSame(READING, s.lastKnown("A"));
        assertSame(READING, s.accountUsagePeriodic("A", false), "2 minutes old, the cooldown of 15 minutes has not passed: no network, the seeded reading");
        assertSame(READING, s.accountUsagePeriodic("A", true), "the active account's cooldown is 5 minutes: still within");
    }

    @Test void aSeedNeverReplacesWhatThisRunHasRead() {
        var s = service();
        var newer = new AccountUsage(20.0, "2026-11-07T20:00:00Z", 51.0, "2026-11-09T20:00:00Z", 41.0);
        s.seed("A", newer, System.currentTimeMillis());
        s.seed("A", READING, System.currentTimeMillis() - 60_000);
        assertSame(newer, s.lastKnown("A"));
    }
}
