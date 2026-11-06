package ccsuite.usagetracker.taskbar;

import module java.base;

/**
 * How the slot band is shared by the cards in it: the usage card alone, or the free accounts' card as well. The free card comes FIRST, so it sits above the usage card
 * on a vertical taskbar and to its left on a horizontal one. The band's length is set by the slot DLL from the registry value {@link #VALUE} (1 or 2 cards), which
 * this class writes; Explorer asks for the size again when the band is toggled in the Toolbars menu or Explorer restarts.
 */
public final class SlotLayout {

    static final String KEY = "HKCU\\Software\\ccsuite\\ccacc", VALUE = "slotCards";
    static volatile boolean freeCard;

    private SlotLayout() {}

    public static boolean freeCard() { return freeCard; }

    /** Says whether the free accounts' card shares the band, and tells the DLL (one small registry write, in the background; a failure only means the band stays as it was). */
    public static void freeCard(boolean on) {
        freeCard = on;
        Thread.ofPlatform().daemon().name("slot-cards").start(() -> {
            try {
                var p = new ProcessBuilder("reg.exe", "add", KEY, "/v", VALUE, "/t", "REG_DWORD", "/d", on ? "2" : "1", "/f").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                p.waitFor();
            } catch (IOException | InterruptedException e) {
                System.err.println("taskbar: could not tell the slot how many cards share it: " + e);
            }
        });
    }

    static int cards() { return freeCard ? 2 : 1; }

    /** Position of a card in the band: the free card is first, the usage card follows (or is alone). */
    static int index(boolean free) { return free ? 0 : freeCard ? 1 : 0; }

    /** {x, y, width, height} of card {@code index} of {@code cards} inside a band of {@code w} by {@code h}: stacked along the long side of a vertical taskbar, side by side on a horizontal one. */
    static int[] target(int w, int h, boolean vertical, int cards, int index) {
        return vertical ? new int[]{0, h / cards * index, w, h / cards} : new int[]{w / cards * index, 0, w / cards, h};
    }
}
