package ccsuite.ccacc;

import java.awt.*;

import static ccsuite.ccacc.CcaccIconKit.*;

final class CcaccIconC {

    static final double BADGE_X = 13, BADGE_Y = 3;

    private CcaccIconC() {}

    static void paint(Graphics2D g) {
        spoke(g, 8, 9.5, 8, 3);
        spoke(g, 8, 9.5, 3, 13);
        spoke(g, 8, 9.5, 13, 13);
        fill(g, disc(8, 9.5, 3), ORANGE);
        fill(g, disc(3, 13, 2), DIM);
        fill(g, disc(13, 13, 2), DIM);
        cut(g, disc(8, 3, 3));
        fill(g, disc(8, 3, 2.5), LIT);
        fill(g, disc(8, 3, 1), CREAM);
    }

    private static void spoke(Graphics2D g, double x1, double y1, double x2, double y2) {
        line(g, x1, y1, x2, y2, 1.6f, DIM);
    }
}
