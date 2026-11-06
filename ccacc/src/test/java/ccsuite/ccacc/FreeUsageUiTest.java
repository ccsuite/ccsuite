package ccsuite.ccacc;

import module java.base;
import ccsuite.base.model.CCAPICredential;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The free usage tracker exists only while a free account does, and is teal where the paid one is orange. */
class FreeUsageUiTest {

    static CCAPICredential account(String id, Integer tier, String key) {
        return new CCAPICredential().id(id).email(id + "@example.org").tier(tier).key(key).orgId("o");
    }

    static FreeUsageUi ui(List<CCAPICredential> accounts) {
        return new FreeUsageUi(new FreeGauges(() -> accounts, (c, m) -> null, Path.of("unused")), new LaneSettings(), null, false);
    }

    @Test void withNoFreeAccountThereIsNothingToShow() {
        assertFalse(ui(List.of()).hasFree());
        assertFalse(ui(List.of(account("P", 1, "k"), account("Q", null, "k"))).hasFree(), "paid accounts do not count");
        assertFalse(ui(List.of(account("F", 0, ""))).hasFree(), "a free account without a session key cannot be read");
    }

    @Test void oneFreeAccountIsEnough() {
        assertTrue(ui(List.of(account("P", 1, "k"), account("F", 0, "k"))).hasFree());
    }

    @Test void theFreeTrackerHasItsOwnColour() {
        assertNotEquals(CcaccApp.COLOR, FreeSummary.COLOR);
        assertEquals("#2aa7b8", FreeSummary.colour(0.1));
        assertEquals("#e0a100", FreeSummary.colour(0.75), "the usage colours keep their meaning");
        assertEquals("#b3261e", FreeSummary.colour(0.95));
    }
}
