package ccsuite.ccacc;

import ccsuite.oauthclient.AccountUsage;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class TotalsTest {

    @Test void accountsAreAveragedAndTheRateIsUsageOverTime() {
        var half = OffsetDateTime.now().plusMinutes(150).toString();
        var u = new LinkedHashMap<String, AccountUsage>();
        u.put("a", new AccountUsage(40.0, half, 90.0, null, 80.0));
        u.put("b", new AccountUsage(20.0, half, 50.0, null, 100.0));
        var c = Totals.of(u);
        assertEquals(30, c.fiveHourUsage(), 1e-9);
        assertEquals(50, c.fiveHourTime(), 0.5);
        assertEquals(70, c.weekUsage(), 1e-9);
        assertEquals(90, c.weekTime(), 1e-9);
    }

    @Test void anUnreachableAccountIsLeftOut() {
        var u = new LinkedHashMap<String, AccountUsage>();
        u.put("a", null);
        u.put("b", new AccountUsage(10.0, null, 10.0, null, 50.0));
        var c = Totals.of(u);
        assertEquals(0, c.fiveHourAccounts());
        assertEquals(1, c.weekAccounts());
    }

    @Test void aBadResetTimeIsNotATimeGone() {
        assertTrue(Double.isNaN(Totals.fiveHourTimeGone("not a time")));
        assertTrue(Double.isNaN(Totals.fiveHourTimeGone(null)));
    }
}
