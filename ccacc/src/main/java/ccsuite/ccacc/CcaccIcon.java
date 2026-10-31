package ccsuite.ccacc;

import java.awt.image.BufferedImage;

import static ccsuite.ccacc.CcaccIconKit.*;

public final class CcaccIcon {

    static final char CONCEPT = 'B';

    private CcaccIcon() {}

    public static BufferedImage draw(int size) {
        return of(CONCEPT, size, null);
    }

    public static BufferedImage drawWithMode(int size, boolean auto) {
        return of(CONCEPT, size, auto);
    }

    public static BufferedImage of(char concept, int size, Boolean auto) {
        var image = canvas(size);
        var g = pen(image);
        try {
            switch (concept) {
                case 'A' -> CcaccIconA.paint(g);
                case 'B' -> CcaccIconB.paint(g);
                case 'C' -> CcaccIconC.paint(g);
                default -> throw new IllegalArgumentException("concept " + concept);
            }
            if (auto != null) mark(g, concept, auto);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void mark(java.awt.Graphics2D g, char concept, boolean auto) {
        switch (concept) {
            case 'A' -> badge(g, auto, CcaccIconA.BADGE_X, CcaccIconA.BADGE_Y);
            case 'B' -> badge(g, auto, CcaccIconB.BADGE_X, CcaccIconB.BADGE_Y);
            default -> badge(g, auto, CcaccIconC.BADGE_X, CcaccIconC.BADGE_Y);
        }
    }
}
