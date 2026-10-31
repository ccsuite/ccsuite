package ccsuite.ccacc;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;

final class CcaccIconKit {

    static final Color
        LIT = new Color(0xe8825f),
        ORANGE = new Color(0xd97757),
        DIM = new Color(0xa05235),
        CREAM = new Color(0xfff3ec),
        ON = new Color(0x3fb950),
        OFF = new Color(0x8b8b8b);

    static final int GRID = 16;

    private CcaccIconKit() {}

    static BufferedImage canvas(int size) {
        return new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    }

    static Graphics2D pen(BufferedImage image) {
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        var s = image.getWidth() / (double) GRID;
        g.scale(s, s);
        return g;
    }

    static Shape disc(double cx, double cy, double r) {
        return new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r);
    }

    static Shape card(double x, double y, double w, double h, double radius) {
        return new RoundRectangle2D.Double(x, y, w, h, 2 * radius, 2 * radius);
    }

    static void fill(Graphics2D g, Shape shape, Color color) {
        g.setColor(color);
        g.fill(shape);
    }

    static void cut(Graphics2D g, Shape shape) {
        var old = g.getComposite();
        g.setComposite(AlphaComposite.Clear);
        g.fill(shape);
        g.setComposite(old);
    }

    static void line(Graphics2D g, double x1, double y1, double x2, double y2, float width, Color color) {
        g.setColor(color);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(x1, y1, x2, y2));
    }

    static void badge(Graphics2D g, boolean auto, double cx, double cy) {
        cut(g, disc(cx, cy, 3.5));
        fill(g, disc(cx, cy, 2.5), auto ? ON : OFF);
    }
}
