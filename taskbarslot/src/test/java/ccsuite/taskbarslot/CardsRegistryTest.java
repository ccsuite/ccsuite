package ccsuite.taskbarslot;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs against a scratch registry key, never the real ones. */
class CardsRegistryTest {
    static final String ROOT = "HKCU\\Software\\ccsuite\\taskbar-test", CARDS = ROOT + "\\cards", LEGACY = ROOT + "\\legacy";
    final Cards cards = new Cards(CARDS, LEGACY);

    @BeforeEach @AfterEach void clean() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Reg.delete(ROOT);
    }

    @Test void registeredCardsComeBackInOrderAndGoAwayWhenUnregistered() {
        assertTrue(cards.register("second", "title two", 20));
        assertTrue(cards.register("first", "title one", 10));
        assertTrue(cards.register("tie", "title three", 20));
        var read = cards.registeredNow();
        assertEquals(java.util.List.of("first", "second", "tie"), read.stream().map(c -> c.id()).toList());
        assertEquals("title two", read.get(1).title());
        assertEquals(20, read.get(1).order());
        assertEquals(1, cards.position("second"));
        assertEquals(-1, cards.position("nobody"));
        assertTrue(cards.unregister("second"));
        assertEquals(java.util.List.of("first", "tie"), cards.registered().stream().map(c -> c.id()).toList());
    }

    @Test void theBandLengthAddsTheLegacyCountAndTheRegisteredCards() {
        assertEquals(1, cards.legacy(), "default");
        assertEquals(1, cards.total());
        assertTrue(cards.register("cgw", "cgw card", 1));
        assertEquals(2, cards.total());
        assertTrue(cards.legacy(2));
        assertEquals(2, cards.legacy());
        assertEquals(3, cards.total());
        assertTrue(cards.legacy(1));
        assertEquals(2, cards.total());
    }

    @Test void aTitleWithSpacesSurvivesTheRoundTrip() {
        assertTrue(cards.register("spaced", "a card with spaces", 3));
        assertEquals("a card with spaces", cards.registeredNow().getFirst().title());
    }
}
