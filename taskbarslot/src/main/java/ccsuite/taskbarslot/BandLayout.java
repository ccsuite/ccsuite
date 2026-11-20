package ccsuite.taskbarslot;

/** Where a card sits inside the band: slots of equal size, stacked down a vertical taskbar and side by side along a horizontal one. */
public final class BandLayout {
    private BandLayout() {}

    /** {x, y, width, height} of slot {@code index} of {@code cards} in a band of {@code w} by {@code h}. */
    public static int[] target(int w, int h, boolean vertical, int cards, int index) { return target(w, h, vertical, cards, index, 1); }

    /** The same for a card that spans {@code span} slots from {@code index}; a card spanning the whole band fills it exactly. */
    public static int[] target(int w, int h, boolean vertical, int cards, int index, int span) {
        var full = span >= cards;
        return vertical
            ? new int[]{0, full ? 0 : h / cards * index, w, full ? h : h / cards * span}
            : new int[]{full ? 0 : w / cards * index, 0, full ? w : w / cards * span, h};
    }
}
