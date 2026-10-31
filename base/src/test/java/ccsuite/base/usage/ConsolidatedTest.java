package ccsuite.base.usage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConsolidatedTest {

    static final double NAN = Double.NaN;

    @Test void averagesAreTakenOverTheAccountsAndTheRateIsUsageOverTime() {
        var c = Consolidated.of(List.of(new double[]{40, 50, 90, 80}, new double[]{20, 50, 50, 100}));
        assertEquals(2, c.fiveHourAccounts());
        assertEquals(30, c.fiveHourUsage(), 1e-9);
        assertEquals(50, c.fiveHourTime(), 1e-9);
        assertEquals(60, c.fiveHourRate(), 1e-9);
        assertEquals(70, c.weekUsage(), 1e-9);
        assertEquals(90, c.weekTime(), 1e-9);
        assertEquals(70.0 / 90 * 100, c.weekRate(), 1e-9);
    }

    @Test void anAccountWithoutDataForAWindowIsLeftOutOfThatWindowOnly() {
        var c = Consolidated.of(List.of(new double[]{40, 50, NAN, NAN}, new double[]{20, 50, 60, 60}));
        assertEquals(2, c.fiveHourAccounts());
        assertEquals(1, c.weekAccounts());
        assertEquals(60, c.weekUsage(), 1e-9);
        assertEquals(100, c.weekRate(), 1e-9);
    }

    @Test void noDataAtAllGivesZerosAndNoRate() {
        var c = Consolidated.of(List.of());
        assertFalse(c.hasWeek());
        assertFalse(c.hasFiveHour());
        assertEquals(0, c.weekRate());
        assertEquals(0, c.fiveHourRate());
    }

    @Test void aWindowJustStartedHasNoRateYet() {
        var c = Consolidated.of(List.<double[]>of(new double[]{5, 0, 5, 0}));
        assertEquals(0, c.weekRate());
    }
}
