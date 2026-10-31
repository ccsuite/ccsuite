package ccsuite.ccacc;

import ccsuite.oauthswap.SwitchResult;
import ccsuite.oauthswap.watch.Decision;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WatchBridgeTest {

    @Test void theWidgetIsToldAtOnceWhenASwitchIsMadeHere() {
        var told = new ArrayList<String>();
        var b = new WatchBridge();
        b.activeChanged = told::add;
        b.onSwitched(new SwitchResult("cat", "bob", "g@example.org"), false);
        assertEquals(java.util.List.of("bob"), told);
        assertEquals("bob", b.activeId);
    }

    @Test void leavingTheLockedAccountOffersTheWayBackWithoutSwitching() {
        var b = new WatchBridge();
        b.onLeftLocked("cat", "ann");
        assertEquals("cat", b.pendingTarget);
    }

    @Test void aPollThatFindsClaudeCodeElsewhereTellsTheWidgetButTheSameAccountDoesNot() {
        var told = new ArrayList<String>();
        var b = new WatchBridge();
        b.activeChanged = told::add;
        b.onPoll("cat", Map.of(), Decision.none("ok"));
        b.onPoll("cat", Map.of(), Decision.none("ok"));
        b.onPoll("ann", Map.of(), Decision.none("ok"));
        assertEquals(java.util.List.of("cat", "ann"), told);
    }
}
