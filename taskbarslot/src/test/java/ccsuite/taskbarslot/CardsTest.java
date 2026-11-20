package ccsuite.taskbarslot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CardsTest {
    static final String KEY = "HKCU\\Software\\ccsuite\\taskbar-test\\cards";
    static final String P = "HKEY_CURRENT_USER\\Software\\ccsuite\\taskbar-test\\cards";

    static final String QUERY = String.join("\r\n",
        P, "",
        P + "\\zeta", "    title    REG_SZ    zeta card", "    order    REG_DWORD    0x5", "",
        P + "\\cgw", "    title    REG_SZ    cgw-taskbar-card", "    order    REG_DWORD    0x1e", "",
        P + "\\Alpha", "    order    REG_DWORD    0x5", "    title    REG_SZ    alpha", "",
        P + "\\bare", "",
        P + "\\cgw\\nested", "    title    REG_SZ    not a card", "");

    @Test void everyDirectSubkeyIsACardSortedByOrderThenId() {
        var ids = Cards.parse(QUERY, KEY).stream().map(c -> c.id()).toList();
        assertEquals(java.util.List.of("bare", "Alpha", "zeta", "cgw"), ids);
    }

    @Test void titleAndOrderAreRead() {
        var cgw = Cards.parse(QUERY, KEY).stream().filter(c -> c.id().equals("cgw")).findFirst().orElseThrow();
        assertEquals("cgw-taskbar-card", cgw.title());
        assertEquals(30, cgw.order());
    }

    @Test void aSubkeyWithoutValuesStillTakesASlotBecauseTheDllCountsSubkeys() {
        var bare = Cards.parse(QUERY, KEY).stream().filter(c -> c.id().equals("bare")).findFirst().orElseThrow();
        assertEquals("", bare.title());
        assertEquals(0, bare.order());
    }

    @Test void noSubkeysIsNoCards() {
        assertTrue(Cards.parse(P + "\r\n", KEY).isEmpty());
        assertTrue(Cards.parse("", KEY).isEmpty());
    }

    @Test void theLegacyCountIsOneOrTwoAndDefaultsToOne() {
        assertEquals(2, Cards.parseLegacy("\r\nHKEY_CURRENT_USER\\Software\\ccsuite\\ccacc\r\n    slotCards    REG_DWORD    0x2\r\n"));
        assertEquals(1, Cards.parseLegacy("    slotCards    REG_DWORD    0x1\r\n"));
        assertEquals(1, Cards.parseLegacy("    slotCards    REG_DWORD    0x7\r\n"));
        assertEquals(1, Cards.parseLegacy(""));
    }

    @Test void theBandIsTheLegacyCardsPlusTheRegisteredOnesWithinTheLimit() {
        assertEquals(1, Cards.total(1, 0));
        assertEquals(2, Cards.total(2, 0));
        assertEquals(2, Cards.total(1, 1));
        assertEquals(3, Cards.total(2, 1));
        assertEquals(Cards.MAX, Cards.total(2, 500));
    }

    @Test void aBadIdOrTitleIsRefusedBeforeItReachesTheRegistry() {
        var c = new Cards(KEY, "HKCU\\Software\\ccsuite\\taskbar-test\\legacy");
        assertThrows(IllegalArgumentException.class, () -> c.register("a\\b", "t", 1));
        assertThrows(IllegalArgumentException.class, () -> c.register("", "t", 1));
        assertThrows(IllegalArgumentException.class, () -> c.register("ok", "has \"quote\"", 1));
        assertThrows(IllegalArgumentException.class, () -> c.register("ok", "t", -1));
    }
}
