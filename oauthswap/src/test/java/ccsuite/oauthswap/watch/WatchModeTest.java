package ccsuite.oauthswap.watch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WatchModeTest {

    @Test void autoMayDoEverything() {
        assertTrue(WatchMode.AUTO.polls());
        assertTrue(WatchMode.AUTO.mayAutoSwitch());
        assertTrue(WatchMode.AUTO.mayRebalance());
    }

    @Test void lockedPollsButNeverMovesTheAccount() {
        assertTrue(WatchMode.LOCKED.polls());
        assertFalse(WatchMode.LOCKED.mayAutoSwitch());
        assertFalse(WatchMode.LOCKED.mayRebalance());
    }

    @Test void pausedDoesNotEvenPoll() {
        assertFalse(WatchMode.PAUSED.polls());
        assertFalse(WatchMode.PAUSED.mayAutoSwitch());
        assertFalse(WatchMode.PAUSED.mayRebalance());
    }
}
