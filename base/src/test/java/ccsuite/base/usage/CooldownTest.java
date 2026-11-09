package ccsuite.base.usage;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static ccsuite.base.usage.Cooldown.*;
import static org.junit.jupiter.api.Assertions.*;

class CooldownTest {

    @Test void theRatioIsUsageOverTimeGoneAndUnknownWithoutBoth() {
        assertEquals(200, ratio(60, 30), 1e-9);
        assertEquals(50, ratio(10, 20), 1e-9);
        assertTrue(Double.isNaN(ratio(10, 0)), "no time gone: no ratio");
        assertTrue(Double.isNaN(ratio(Double.NaN, 20)));
        assertTrue(Double.isNaN(ratio(10, Double.NaN)));
    }

    @Test void aWeeklyRatioAlreadyUnder100NeedsNoWait() {
        assertEquals(Duration.ZERO, week(30, 40));
        assertEquals(Duration.ZERO, week(40, 40));
        assertEquals("0m", terse(week(30, 40)));
    }

    @Test void aGapOfOneSeventhOfTheWeekIsOneDay() {
        assertEquals(Duration.ofDays(1), week(100.0 / 7 + 10, 10));
        assertEquals("1d", terse(week(100.0 / 7 + 10, 10)));
    }

    @Test void theWaitIsRoundedUpToAWholeMinuteAndCappedAtTheWeek() {
        assertEquals(Duration.ofMinutes(101), week(1.0001, 0), "1% of 10080 minutes is 100.8");
        assertEquals(Duration.ofDays(7), week(250, 0));
    }

    @Test void aWeekNotKnownHasNoWait() {
        assertNull(week(Double.NaN, 10));
        assertNull(week(10, Double.NaN));
        assertEquals("-", terse(null));
    }

    @Test void itIsWrittenTersely() {
        assertEquals("1d5h", terse(Duration.ofHours(29)));
        assertEquals("2d", terse(Duration.ofDays(2)));
        assertEquals("5h", terse(Duration.ofHours(5)));
        assertEquals("5h30m", terse(Duration.ofMinutes(330)));
        assertEquals("2m", terse(Duration.ofMinutes(2)));
        assertEquals("1d", terse(Duration.ofMinutes(1440 + 40)), "minutes are dropped once there are days");
    }
}
