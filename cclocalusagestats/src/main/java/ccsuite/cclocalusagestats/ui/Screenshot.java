package ccsuite.cclocalusagestats.ui;

import java.awt.image.BufferedImage;
import java.io.File;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/** Test hook: after a delay, saves the window as a PNG and quits. Only used when -Dstats.screenshot is set. */
final class Screenshot {

    private Screenshot() {}

    static void after(Scene scene, int ms, String file) {
        var p = new PauseTransition(Duration.millis(ms));
        p.setOnFinished(e -> {
            try {
                var img = scene.snapshot(null);
                var out = new BufferedImage((int) img.getWidth(), (int) img.getHeight(), BufferedImage.TYPE_INT_ARGB);
                var reader = img.getPixelReader();
                for (var y = 0; y < out.getHeight(); y++) for (var x = 0; x < out.getWidth(); x++) out.setRGB(x, y, reader.getArgb(x, y));
                ImageIO.write(out, "png", new File(file));
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            Platform.exit();
        });
        p.play();
    }
}
