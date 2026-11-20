package ccsuite.taskbarslot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BandLayoutTest {

    @Test void aCardSpanningTheWholeBandFillsItExactlyEvenWhenTheSizeDoesNotDivide() {
        assertArrayEquals(new int[]{0, 0, 101, 44}, BandLayout.target(101, 44, false, 1, 0));
        assertArrayEquals(new int[]{0, 0, 101, 89}, BandLayout.target(101, 89, true, 2, 0, 2));
        assertArrayEquals(new int[]{0, 0, 101, 44}, BandLayout.target(101, 44, false, 2, 0, 2));
    }

    @Test void aCardSpanningTwoOfThreeSlots() {
        assertArrayEquals(new int[]{0, 0, 202, 44}, BandLayout.target(303, 44, false, 3, 0, 2));
        assertArrayEquals(new int[]{0, 44, 152, 88}, BandLayout.target(152, 132, true, 3, 1, 2));
    }

    @Test void slotsOfFiveSitSideBySide() {
        for (var i = 0; i < 5; i++) assertArrayEquals(new int[]{101 * i, 0, 101, 44}, BandLayout.target(505, 44, false, 5, i));
    }
}
