package ccsuite.usagetracker.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WidgetPlaceTest {

    // 1366x768 with a 40 px taskbar at the bottom: the usable part is 1366x728
    static final double W = 1366, H = 728;

    @Test void theFirstWindowStandsBottomRightInsideTheUsableArea() {
        var at = WidgetPlace.defaultAt(0, 0, W, H, 120, 200, 0);
        assertEquals(W - 8 - 120, at[0], 1e-9);
        assertEquals(H - 8 - 200, at[1], 1e-9);
    }

    @Test void theSecondWindowStandsLeftOfTheFirstWithAGap() {
        var first = WidgetPlace.defaultAt(0, 0, W, H, 120, 200, 0);
        var second = WidgetPlace.defaultAt(0, 0, W, H, 120, 200, 1);
        assertEquals(first[0] - 120 - 6, second[0], 1e-9);
        assertEquals(first[1], second[1], 1e-9);
    }

    @Test void aTaskbarOnTheLeftOrTopMovesTheUsableAreaAndTheWindowFollows() {
        var at = WidgetPlace.defaultAt(48, 0, W, H, 120, 200, 0);   // a 48 px taskbar on the left edge
        assertTrue(at[0] >= 48);
        var top = WidgetPlace.defaultAt(0, 40, W, 768, 120, 200, 0);   // a taskbar on top
        assertEquals(768 - 8 - 200, top[1], 1e-9);
    }

    @Test void aWindowTallerThanTheAreaStartsAtItsTopEdgeNotAboveIt() {
        var at = WidgetPlace.defaultAt(0, 0, W, 300, 120, 400, 0);
        assertEquals(0, at[1], 1e-9);
    }

    @Test void growingByARowKeepsTheBottomEdgeInPlace() {
        var small = WidgetPlace.defaultAt(0, 0, W, H, 120, 100, 0);
        var grown = WidgetPlace.defaultAt(0, 0, W, H, 120, 160, 0);
        assertEquals(small[1] + 100, grown[1] + 160, 1e-9);
    }

    @Test void fitPullsAWindowThatHangsOverTheEdgesBack() {
        var at = WidgetPlace.fit(0, 0, W, H, W - 50, H - 50, 120, 200);
        assertEquals(W - 120, at[0], 1e-9);
        assertEquals(H - 200, at[1], 1e-9);
        var inside = WidgetPlace.fit(0, 0, W, H, 100, 100, 120, 200);
        assertArrayEquals(new double[]{100, 100}, inside, 1e-9);
    }
}
