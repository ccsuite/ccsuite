package ccsuite.oauthswap.tray;

import ccsuite.oauthclient.QuotaHealth;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;

/**
 * Shared tray-icon renderer: a ring proportional to a normalized ratio (0..1+, colored by
 * {@link QuotaHealth}) around a short monogram, optionally set inside a distinguishing outer
 * {@link IconFrame} so different tray apps read apart from each other at a glance. One static
 * frame per call — no animation, matching "not too much, not irritating".
 */
public final class TrayIconRenderer {

    private static final int SIZE = 32;
    private static final int RING_WIDTH = 6;

    private TrayIconRenderer() {}

    public static Image render(String monogram, Double ratio, QuotaHealth health, IconFrame frame) {
        var img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        try {
            drawBadge(g, frame);
            drawRing(g, ratio, color(health));
            drawMonogram(g, monogram);
        } finally {
            g.dispose();
        }
        return img;
    }

    /** Backing disc so the ring/monogram read on any tray background — light or dark/black.
     *  Tinted per {@link IconFrame} so the two apps read apart at a glance even at real (16-24px)
     *  tray size, where a thin outline (the original distinguishing idea) disappears entirely. */
    private static void drawBadge(Graphics2D g, IconFrame frame) {
        var inset = RING_WIDTH / 2.0 + 2;
        var shape = new Ellipse2D.Double(inset, inset, SIZE - 2 * inset, SIZE - 2 * inset);
        g.setColor(frame == IconFrame.ROUNDED_SQUARE ? new Color(214, 232, 250, 240) : new Color(245, 245, 245, 240));
        g.fill(shape);
    }

    /** Grey track always drawn (dark enough to read against the light badge on its own); a
     *  colored arc on top sweeps clockwise from 12 o'clock, capped at a full circle past 100%
     *  (over-pace is communicated by color, not an overflowing arc). */
    private static void drawRing(Graphics2D g, Double ratio, Color ringColor) {
        var inset = RING_WIDTH / 2.0 + 2;
        var track = new Ellipse2D.Double(inset, inset, SIZE - 2 * inset, SIZE - 2 * inset);
        g.setStroke(new BasicStroke(RING_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(90, 90, 90, 110));
        g.draw(track);
        if (ratio == null) return;
        var degrees = Math.min(ratio, 1.0) * 360.0;
        var arc = new Arc2D.Double(inset, inset, SIZE - 2 * inset, SIZE - 2 * inset, 90, -degrees, Arc2D.OPEN);
        g.setColor(ringColor);
        g.draw(arc);
    }

    private static void drawMonogram(Graphics2D g, String monogram) {
        if (monogram == null || monogram.isBlank()) return;
        var text = monogram.length() > 2 ? monogram.substring(0, 2) : monogram;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        g.setColor(new Color(40, 40, 40));
        var fm = g.getFontMetrics();
        var x = (SIZE - fm.stringWidth(text)) / 2.0;
        var y = (SIZE - fm.getHeight()) / 2.0 + fm.getAscent();
        g.drawString(text, (float) x, (float) y);
    }

    private static Color color(QuotaHealth h) {
        return switch (h) {
            case DANGER -> new Color(0xD9, 0x30, 0x25);
            case WARN -> new Color(0xE6, 0xA0, 0x17);
            case OK -> new Color(0x2E, 0xA0, 0x43);
            case UNKNOWN -> Color.GRAY;
        };
    }
}
