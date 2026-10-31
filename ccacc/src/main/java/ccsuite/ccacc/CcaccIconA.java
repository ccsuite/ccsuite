package ccsuite.ccacc;

import java.awt.*;

import static ccsuite.ccacc.CcaccIconKit.*;

final class CcaccIconA {

    static final double BADGE_X = 13, BADGE_Y = 13;

    private CcaccIconA() {}

    static void paint(Graphics2D g) {
        back(g, 6, 1, DIM);
        back(g, 3, 4, ORANGE);
        front(g, 0, 7);
    }

    private static void back(Graphics2D g, double x, double y, Color color) {
        cut(g, card(x - 1, y - 1, 11, 11, 3));
        fill(g, card(x, y, 9, 9, 2), color);
    }

    private static void front(Graphics2D g, double x, double y) {
        cut(g, card(x - 1, y - 1, 11, 11, 3));
        var shape = card(x, y, 9, 9, 2);
        fill(g, shape, LIT);
        var keep = g.getClip();
        g.clip(shape);
        fill(g, disc(x + 4.5, y + 3.3, 1.6), CREAM);
        fill(g, disc(x + 4.5, y + 8.7, 3), CREAM);
        g.setClip(keep);
    }
}
