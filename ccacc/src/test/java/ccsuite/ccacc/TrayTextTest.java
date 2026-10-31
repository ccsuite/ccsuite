package ccsuite.ccacc;

import ccsuite.oauthclient.AccountUsage;
import ccsuite.oauthswap.watch.WatchMode;
import org.junit.jupiter.api.Test;

import static ccsuite.ccacc.TrayText.*;
import static org.junit.jupiter.api.Assertions.*;

class TrayTextTest {

    @Test void monogramIsTwoLettersUpperCased() {
        assertEquals("AN", monogram("ann"));
        assertEquals("K", monogram("k"));
        assertEquals("?", monogram(null));
    }

    @Test void unknownNumbersShowAsQuestionMark() {
        assertEquals("?", util(null));
        assertEquals("?", weekFree(null));
        assertEquals("?", avg(null));
        assertNull(ratioOf(null));
    }

    @Test void numbersAreRoundedPercents() {
        var u = new AccountUsage(38.0, null, 96.4, null, 50.0);
        assertEquals("96%", util(u));
        assertEquals("4%", weekFree(u));
        assertEquals("193%", avg(u));
        assertEquals(1.928, ratioOf(u), 0.001);
    }

    @Test void statusLineNamesTheModeAndTheAccount() {
        var u = new AccountUsage(38.0, null, 50.0, null, 50.0);
        assertEquals("ann  5h 38%  week 50% (avg 100%)  ·  locked", status("ann", u, WatchMode.LOCKED));
        assertEquals("Claude Code: no managed account  ·  paused", status(null, null, WatchMode.PAUSED));
    }
}
