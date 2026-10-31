package ccsuite.usagetracker.ui;

import javafx.scene.image.Image;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One mark for the whole app — a cyan disc with a white rim — drawn at any size, so the tray
 * icon and the window/taskbar/alt-tab icon are the same thing.
 */
public final class UsageAppIcon {

    private static final Color
        FILL = new Color(0, 191, 255),
        RIM = Color.WHITE;

    private static final int
        TRAY_SIZE = 32;

    private static final int[] WINDOW_SIZES = {16, 24, 32, 48, 64, 128, 256};

    private UsageAppIcon() {}

    public static BufferedImage draw(int size) {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        try {
            var rim = Math.max(1f, size / 16f);
            var disc = new Ellipse2D.Float(rim / 2f, rim / 2f, size - rim, size - rim);
            g.setColor(FILL);
            g.fill(disc);
            g.setColor(RIM);
            g.setStroke(new BasicStroke(rim));
            g.draw(disc);
        } finally {
            g.dispose();
        }
        return image;
    }

    public static java.awt.Image trayImage() {
        return draw(TRAY_SIZE);
    }

    public static List<Image> windowIcons() {
        return Arrays.stream(WINDOW_SIZES).mapToObj(UsageAppIcon::fxImage).filter(Objects::nonNull).toList();
    }

    private static Image fxImage(int size) {
        try (var out = new ByteArrayOutputStream()) {
            ImageIO.write(draw(size), "png", out);
            return new Image(new ByteArrayInputStream(out.toByteArray()));
        } catch (Exception e) {
            System.err.println("Failed to build " + size + "px window icon: " + e.getMessage());
            return null;
        }
    }
}
