package ccsuite.ccacc;

import javafx.scene.image.Image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** A drawn picture into JavaFX through its image loader (a snapshot or pixel-copied WritableImage is ignored as a window icon on Windows). */
final class FxImages {

    static Image of(BufferedImage img) {
        try {
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bytes);
            return new Image(new ByteArrayInputStream(bytes.toByteArray()));
        } catch (IOException e) {
            throw new IllegalStateException("cannot encode the icon", e);
        }
    }

    /** The ccacc icon at the sizes Windows picks from for a window's title bar and taskbar button. */
    static java.util.List<Image> windowIcons() {
        return java.util.stream.IntStream.of(16, 24, 32, 48, 64, 256).mapToObj(s -> of(CcaccIcon.draw(s))).toList();
    }

    private FxImages() {}
}
