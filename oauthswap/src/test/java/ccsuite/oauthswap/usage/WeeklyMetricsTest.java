package ccsuite.oauthswap.usage;

import ccsuite.oauthclient.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static ccsuite.oauthclient.QuotaHealth.*;

class WeeklyMetricsTest {

    private static AccountUsage wk(Double sevenDayPct, Double elapsedPct) {
        return new AccountUsage(null, null, sevenDayPct, null, elapsedPct);
    }

    @Test void averageIsUsedOverElapsed() {
        assertEquals(200.0, wk(50.0, 25.0).sevenDayAverage(), 1e-9);  // double-pace
        assertEquals(100.0, wk(40.0, 40.0).sevenDayAverage(), 1e-9);  // exactly on pace
        assertEquals(50.0,  wk(20.0, 40.0).sevenDayAverage(), 1e-9);  // half-pace
    }

    @Test void averageNullWhenInputsMissing() {
        assertNull(wk(null, 40.0).sevenDayAverage());
        assertNull(wk(40.0, null).sevenDayAverage());
        assertNull(wk(40.0, 0.0).sevenDayAverage());   // no time elapsed yet
    }

    @Test void sevenDayHeadroomIsComplement() {
        assertEquals(70.0, wk(30.0, 10.0).sevenDayHeadroom(), 1e-9);
        assertNull(wk(null, 10.0).sevenDayHeadroom());
    }

    @Test void healthThresholds() {
        assertEquals(DANGER, ofAverage(140.0));
        assertEquals(DANGER, ofAverage(100.0));
        assertEquals(WARN,   ofAverage(99.9));
        assertEquals(WARN,   ofAverage(95.0));
        assertEquals(OK,     ofAverage(94.9));
        assertEquals(UNKNOWN, ofAverage(null));
    }

    @Test void weeklyHealthDelegatesToAverage() {
        assertEquals(DANGER, wk(80.0, 40.0).weeklyHealth());  // avg 200, usage 80
        assertEquals(OK,     wk(20.0, 40.0).weeklyHealth());  // avg 50
        assertEquals(UNKNOWN, wk(40.0, 0.0).weeklyHealth());  // avg null
    }

    @Test void earlyWeekLowUsageGetsGrace() {
        // 10% used after ~2% of the week = 500% pace, but only 10% of weekly quota gone → don't panic.
        assertEquals(500.0, wk(10.0, 2.0).sevenDayAverage(), 1e-9);
        assertEquals(OK, wk(10.0, 2.0).weeklyHealth());
        assertEquals(OK, QuotaHealth.ofWeekly(500.0, 10.0));
        // at/above the 20% usage floor the burn-rate is taken at face value again
        assertEquals(DANGER, QuotaHealth.ofWeekly(150.0, 20.0));
    }
}
