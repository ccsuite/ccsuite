package ccsuite.usagetracker.taskbar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlotLayoutTest {

    @Test void oneAndTwoCardsGiveTheNumbersTheyAlwaysDid() {
        assertArrayEquals(new int[]{0, 0, 101, 44}, SlotLayout.target(101, 44, false, 1, 0));
        assertArrayEquals(new int[]{0, 0, 100, 88}, SlotLayout.target(100, 88, true, 1, 0));
        assertArrayEquals(new int[]{0, 0, 100, 44}, SlotLayout.target(100, 88, true, 2, 0));
        assertArrayEquals(new int[]{0, 44, 100, 44}, SlotLayout.target(100, 88, true, 2, 1));
        assertArrayEquals(new int[]{50, 0, 50, 44}, SlotLayout.target(101, 44, false, 2, 1), "an odd width keeps the same floor");
    }

    @Test void threeAndFiveCardsAreStackedDownAVerticalBand() {
        assertArrayEquals(new int[]{0, 0, 152, 44}, SlotLayout.target(152, 132, true, 3, 0));
        assertArrayEquals(new int[]{0, 88, 152, 44}, SlotLayout.target(152, 132, true, 3, 2));
        assertArrayEquals(new int[]{0, 176, 152, 44}, SlotLayout.target(152, 220, true, 5, 4));
    }

    @Test void threeAndFiveCardsRunAlongAHorizontalBand() {
        assertArrayEquals(new int[]{101, 0, 101, 44}, SlotLayout.target(303, 44, false, 3, 1));
        assertArrayEquals(new int[]{404, 0, 101, 44}, SlotLayout.target(505, 44, false, 5, 4));
    }
}
