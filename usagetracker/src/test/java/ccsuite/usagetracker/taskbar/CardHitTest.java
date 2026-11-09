package ccsuite.usagetracker.taskbar;

import org.junit.jupiter.api.Test;

import static ccsuite.usagetracker.taskbar.CardView.Hit.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** What a press in the card's button strip means. The card is 47 high at 125% scaling and the strip 23 wide. */
class CardHitTest {

    static final double H = 47;

    @Test void theTopLineIsTheAccountName() {
        assertEquals(NAME, CardView.hit(5, 3, H));
        assertEquals(NAME, CardView.hit(18, 8, H));
    }

    @Test void theTierMarkRightOfTheCheckMarkReadsTheAccountAgain() {
        assertEquals(TYPE, CardView.hit(CardView.BTN / 2 + 5, H * 0.38, H));
        assertEquals(TYPE, CardView.hit(CardView.BTN / 2, H * 0.30, H));
    }

    @Test void theLowerLeftArrowGoesBackAndTheRestOfTheLowerStripGoesForward() {
        assertEquals(BACK, CardView.hit(CardView.BTN / 2 - 5, H * 0.82, H));
        assertEquals(FORWARD, CardView.hit(CardView.BTN / 2 + 6, H * 0.82, H));
        assertEquals(FORWARD, CardView.hit(CardView.BTN / 2 - 5, H * 0.55, H), "the page counter goes forward as before");
        assertEquals(FORWARD, CardView.hit(3, H * 0.38, H), "the check mark side is not the tier mark");
    }
}
