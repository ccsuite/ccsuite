package ccsuite.ccacc;

import java.awt.*;

import static ccsuite.ccacc.CcaccIconKit.*;

final class CcaccIconB {

    static final double BADGE_X = 13, BADGE_Y = 13;

    private static final float WIDTH = 2f;

    private CcaccIconB() {}

    static void paint(Graphics2D g) {
        fill(g, disc(8, 8, 8), LIT);
        arrowRight(g, 5);
        arrowLeft(g, 11);
    }

    private static void arrowRight(Graphics2D g, double y) {
        line(g, 4, y, 12, y, WIDTH, CREAM);
        line(g, 9.5, y - 2.5, 12, y, WIDTH, CREAM);
        line(g, 9.5, y + 2.5, 12, y, WIDTH, CREAM);
    }

    private static void arrowLeft(Graphics2D g, double y) {
        line(g, 4, y, 12, y, WIDTH, CREAM);
        line(g, 6.5, y - 2.5, 4, y, WIDTH, CREAM);
        line(g, 6.5, y + 2.5, 4, y, WIDTH, CREAM);
    }
}
