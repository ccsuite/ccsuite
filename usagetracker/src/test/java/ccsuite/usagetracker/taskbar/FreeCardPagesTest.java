package ccsuite.usagetracker.taskbar;

import module java.base;
import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.Usage;
import ccsuite.base.model.UsageData;
import ccsuite.usagetracker.model.AccountUsage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The free accounts' card: ALL is the first page, and the two cards share the slot band. */
class FreeCardPagesTest {

    static AccountUsage account(String id, double five) {
        return new AccountUsage(new CCAPICredential().id(id).tier(0).active(true), new UsageData().fiveHour(new Usage().utilization(five).resetsAt(OffsetDateTime.now().plusHours(2))));
    }

    @Test void allIsTheFirstPageOfTheFreeCard() {
        var view = new CardView();
        view.setFree(List.of(account("A", 10), account("B", 30)), account("ALL", 20));
        assertEquals(3, view.count());
        assertEquals("ALL", view.current().credential().id(), "the first screen is the overall");
        view.page(1);
        assertEquals("A", view.current().credential().id());
        view.page(1);
        assertEquals("B", view.current().credential().id());
        view.page(1);
        assertEquals("ALL", view.current().credential().id(), "and it wraps round to it");
        assertFalse(view.currentIsActive());
    }

    @Test void aSingleFreeAccountHasNoAllPage() {
        var view = new CardView();
        view.setFree(List.of(account("A", 10)), account("ALL", 10));
        assertEquals(1, view.count());
        assertEquals("A", view.current().credential().id());
    }

    @Test void thePaidCardKeepsAllLast() {
        var view = new CardView();
        view.set(List.of(account("A", 10), account("B", 30)));
        assertEquals("A", view.current().credential().id());
        view.page(2);
        assertEquals("ALL", view.current().credential().id());
    }

    @Test void theBandIsSharedFreeFirst() {
        SlotLayout.freeCard = false;
        assertEquals(1, SlotLayout.cards());
        assertEquals(0, SlotLayout.index(false));
        SlotLayout.freeCard = true;
        try {
            assertEquals(2, SlotLayout.cards());
            assertEquals(0, SlotLayout.index(true), "the free card first");
            assertEquals(1, SlotLayout.index(false), "the usage card after it");
            assertArrayEquals(new int[]{0, 0, 100, 44}, SlotLayout.target(100, 88, true, 2, 0), "vertical taskbar: the free card above");
            assertArrayEquals(new int[]{0, 44, 100, 44}, SlotLayout.target(100, 88, true, 2, 1));
            assertArrayEquals(new int[]{0, 0, 101, 44}, SlotLayout.target(202, 44, false, 2, 0), "horizontal taskbar: the free card to the left");
            assertArrayEquals(new int[]{101, 0, 101, 44}, SlotLayout.target(202, 44, false, 2, 1));
            assertArrayEquals(new int[]{0, 0, 101, 44}, SlotLayout.target(101, 44, false, 1, 0), "alone, it fills the band as before");
        } finally {
            SlotLayout.freeCard = false;
        }
    }
}
