package ccsuite.usagetracker.taskbar;

import module java.base;
import ccsuite.taskbarslot.BandLayout;
import ccsuite.taskbarslot.Cards;

/**
 * How the slot band is shared by ccacc's cards: the usage card alone, or the free accounts' card as well. The free card comes FIRST, so it sits above the usage card
 * on a vertical taskbar and to its left on a horizontal one. The band's length is set by the slot DLL from the registry value {@code slotCards} (1 or 2 cards), which
 * this class writes; Explorer asks for the size again when the band is toggled in the Toolbars menu or Explorer restarts. Cards of other tools come after these
 * (see {@link ccsuite.taskbarslot.Cards}).
 */
public final class SlotLayout {

    static volatile boolean freeCard;

    private SlotLayout() {}

    public static boolean freeCard() { return freeCard; }

    /** Says whether the free accounts' card shares the band, and tells the DLL (one small registry write, in the background; a failure only means the band stays as it was). */
    public static void freeCard(boolean on) {
        freeCard = on;
        Thread.ofPlatform().daemon().name("slot-cards").start(() -> {
            if (!Cards.live().legacy(on ? 2 : 1)) System.err.println("taskbar: could not tell the slot how many cards share it");
        });
    }

    static int cards() { return freeCard ? 2 : 1; }

    /** Position of a card in the band: the free card is first, the usage card follows (or is alone). */
    static int index(boolean free) { return free ? 0 : freeCard ? 1 : 0; }

    /** {x, y, width, height} of card {@code index} of {@code cards} inside a band of {@code w} by {@code h}: stacked along the long side of a vertical taskbar, side by side on a horizontal one. */
    static int[] target(int w, int h, boolean vertical, int cards, int index) { return BandLayout.target(w, h, vertical, cards, index); }
}
