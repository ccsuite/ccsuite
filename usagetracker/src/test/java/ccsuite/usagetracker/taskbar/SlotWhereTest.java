package ccsuite.usagetracker.taskbar;

import ccsuite.taskbarslot.BandLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** ccacc's one-card and two-card band must lay out exactly as before the band became shared with other tools. */
class SlotWhereTest {

    @Test void theUsageCardAloneFillsTheOneCardBand() {
        assertArrayEquals(new int[]{0, 1}, Slot.where(1, 1, 0));
    }

    @Test void theTwoCardBandGivesTheFreeCardTheFirstSlotAndTheUsageCardTheSecond() {
        assertArrayEquals(new int[]{0, 1}, Slot.where(2, 2, 0));
        assertArrayEquals(new int[]{1, 1}, Slot.where(2, 2, 1));
    }

    @Test void anOldOneCardBandStillHoldsTheUsageCardWhenTwoAreWanted() {
        assertArrayEquals(new int[]{0, 1}, Slot.where(2, 1, 1));
    }

    @Test void aTwoCardBandKeepsTheOneUsageCardFillingItUntilToggled() {
        assertArrayEquals(new int[]{0, 2}, Slot.where(1, 2, 0));
    }

    @Test void theSlotsMapToTodaysPixels() {
        assertArrayEquals(SlotLayout.target(100, 88, true, 2, 1), BandLayout.target(100, 88, true, 2, 1, 1));
        assertArrayEquals(SlotLayout.target(101, 89, false, 1, 0), BandLayout.target(101, 89, false, 2, 0, 2), "one card filling a two-card band");
    }
}
