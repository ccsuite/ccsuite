package ccsuite.oauthswap.watch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ManualHoldTest {

    static final long MIN = 60_000;

    @Test void aSwitchAskedForHoldsRebalancingForHalfAnHour() {
        var h = new ManualHold();
        h.observe("cat", 0);
        h.manual("bob", 0);
        h.observe("bob", MIN);
        assertFalse(h.allowsRebalance(29 * MIN));
        assertTrue(h.allowsRebalance(30 * MIN));
    }

    @Test void claudeCodeTurningUpOnAnotherAccountIsTheUsersSwitch() {
        var h = new ManualHold();
        h.observe("cat", 0);
        assertTrue(h.allowsRebalance(MIN));
        h.observe("bob", 2 * MIN);
        assertFalse(h.allowsRebalance(3 * MIN));
        assertEquals(29, h.minutesLeft(3 * MIN));
    }

    @Test void theWatchersOwnSwitchIsNotMistakenForTheUsers() {
        var h = new ManualHold();
        h.observe("cat", 0);
        h.automatic("dan");
        h.observe("dan", MIN);
        assertTrue(h.allowsRebalance(2 * MIN));
    }

    @Test void observeSaysWhenTheAccountMovedWithoutTheWatcher() {
        var h = new ManualHold();
        assertFalse(h.observe("cat", 0));
        assertFalse(h.observe("cat", MIN));
        assertTrue(h.observe("ann", 2 * MIN));
        h.automatic("dan");
        assertFalse(h.observe("dan", 3 * MIN));
    }

    @Test void theFirstPollIsNotAChange() {
        var h = new ManualHold();
        h.observe("cat", 0);
        assertTrue(h.allowsRebalance(0));
    }
}
