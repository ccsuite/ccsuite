package ccsuite.cclocalusagestats.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import javafx.scene.image.Image;

final class Icon {

    private Icon() {}

    /** Orange tile with three rising bars, drawn here and passed through PNG so the title bar accepts it. */
    static Image load() {
        try {
            var img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            var g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xd97757));
            g.fillRoundRect(2, 2, 60, 60, 16, 16);
            g.setColor(Color.WHITE);
            for (var i = 0; i < 3; i++) g.fillRoundRect(12 + i * 15, 46 - (i + 1) * 12, 11, (i + 1) * 12 + 4, 4, 4);
            g.dispose();
            var out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return new Image(new ByteArrayInputStream(out.toByteArray()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
