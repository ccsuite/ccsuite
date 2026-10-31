package ccsuite.usagetracker.taskbar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CardLayoutTest {

    /** The taskbar scales the card by 125%, so its drawing height is 47 units: the four bars and the line under them must fit. */
    @Test void theFourBarsAndTheLineBelowFitTheCardAt47AndAtTallerSizes() {
        for (var h : new double[]{47, 52, 59, 70}) {
            var bottom = CardView.top(h) + CardView.rowsHeight(h) + CardView.FOOTER_H;
            assertTrue(CardView.top(h) >= 0, "top at " + h);
            assertTrue(bottom <= h, "bottom " + bottom + " at " + h);
        }
    }

    @Test void aTallCardKeepsTheUsualGapAndALowOneTightens() {
        assertEquals(CardView.GAP, CardView.gap(59));
        assertEquals(CardView.TIGHT_GAP, CardView.gap(47));
    }

    @Test void aCardTooLowForTheLineHasNone() {
        assertTrue(CardView.FOOTER_MIN_H > 40);
        assertEquals((30 - CardView.rowsHeight(30)) / 2, CardView.top(30) , 1e-9);
    }
}
