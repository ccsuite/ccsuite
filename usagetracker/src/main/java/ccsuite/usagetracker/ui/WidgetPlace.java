package ccsuite.usagetracker.ui;

/**
 * Where the usage window goes when nothing is remembered: the right edge of the usable part of the screen (the part the taskbar leaves free, so never under it),
 * bottom aligned, so it sits next to the clock whichever edge the taskbar is on. A second window (the free accounts') goes left of the first one. On a 1366x768
 * screen the usable part is about 1366x728, and a 120 px wide window with six rows fits with room to spare.
 */
public final class WidgetPlace {

    static final double MARGIN = 8, GAP = 6;

    private WidgetPlace() {}

    /** {x, y} of the window's top left corner; {@code slot} 0 is the first window, 1 the one beside it. Always inside the usable area when the window fits in it. */
    public static double[] defaultAt(double minX, double minY, double maxX, double maxY, double width, double height, int slot) {
        var x = maxX - MARGIN - width - slot * (width + GAP);
        var y = maxY - MARGIN - height;
        return new double[]{Math.max(minX, x), Math.max(minY, y)};
    }

    /** The same window moved back inside the usable area (a window grown by a new row must not hang below the taskbar). */
    public static double[] fit(double minX, double minY, double maxX, double maxY, double x, double y, double width, double height) {
        return new double[]{Math.max(minX, Math.min(x, maxX - width)), Math.max(minY, Math.min(y, maxY - height))};
    }
}
