package ccsuite.usagetracker.ui;

import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.CCAPIsCredentials;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GlowPulseTest {

    @Test void theCalmBeatIsYellowToGoldAndNeverRed() {
        assertEquals(Color.web("#FFE600"), GlowPulse.fire(0, false));
        assertEquals(Color.web("#FFB000"), GlowPulse.fire(GlowPulse.PERIOD_MS / 2, false));
    }

    @Test void theDangerBeatTurnsRed() {
        assertEquals(Color.web("#FF2200"), GlowPulse.fire(GlowPulse.PERIOD_MS / 2, true));
        assertEquals(GlowPulse.fire(0, true), GlowPulse.fire(GlowPulse.PERIOD_MS, true));
    }

    @Test void theGlowSwellsToTheDeepEnd() {
        assertEquals(4, GlowPulse.radius(0), 1e-9);
        assertEquals(13, GlowPulse.radius(GlowPulse.PERIOD_MS / 2), 1e-9);
    }

    @Test void dangerIsHalfTheFiveHourQuotaSpentFasterThanTheWindowPasses() {
        assertTrue(GlowPulse.danger(60, 40, 0, 0));
        assertFalse(GlowPulse.danger(60, 80, 0, 0), "used more than half, but slower than time");
        assertFalse(GlowPulse.danger(40, 20, 0, 0), "fast, but not yet half used");
    }

    @Test void dangerIsTheWeekSpentFasterThanItPassesOnceHalfOfItIsGone() {
        assertTrue(GlowPulse.danger(0, 0, 70, 60));
        assertFalse(GlowPulse.danger(0, 0, 40, 30), "fast, but less than half the week gone");
        assertFalse(GlowPulse.danger(0, 0, 50, 60), "half the week gone, but slower than time");
    }

    @Test void noDataIsNoDanger() { assertFalse(GlowPulse.danger(null)); }

    static CCAPIsCredentials two() {
        var all = new CCAPIsCredentials();
        all.credentials().add(new CCAPICredential().id("dan").claudecodeactive(true));
        all.credentials().add(new CCAPICredential().id("cat"));
        return all;
    }

    @Test void theLiveLoginDecidesWhoIsActiveNotTheOldFlag() {
        var all = two();
        LiveActive.apply(all, "cat");
        assertTrue(all.getById("cat").claudecodeactive());
        assertFalse(all.getById("dan").claudecodeactive());
    }

    @Test void noManagedAccountMarksNone() {
        var all = two();
        LiveActive.apply(all, null);
        assertTrue(all.credentials().stream().noneMatch(CCAPICredential::claudecodeactive));
    }
}
