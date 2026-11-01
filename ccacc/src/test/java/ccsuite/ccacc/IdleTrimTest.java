package ccsuite.ccacc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IdleTrimTest {

    static final long MIN = 60_000_000_000L;

    @Test void notWhileTheAppWasUsedLessThanTwoMinutesAgo() {
        assertFalse(IdleTrim.due(10 * MIN, 9 * MIN, 0));
        assertFalse(IdleTrim.due(10 * MIN, 8 * MIN + 1, 0));
    }

    @Test void onceItHasBeenQuietForTwoMinutes() {
        assertTrue(IdleTrim.due(10 * MIN, 8 * MIN, 0));
    }

    @Test void repeatsOnlyAfterFourMinutesWhileItStaysQuiet() {
        assertFalse(IdleTrim.due(20 * MIN, 1 * MIN, 17 * MIN), "trimmed 3 minutes ago");
        assertTrue(IdleTrim.due(20 * MIN, 1 * MIN, 16 * MIN), "trimmed 4 minutes ago");
    }

    @Test void aTrimThatCannotBeDoneNeverThrows() {
        assertDoesNotThrow(IdleTrim::trim);
    }
}
