package ccsuite.usagetracker.taskbar;

import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.Usage;
import ccsuite.base.model.UsageData;
import ccsuite.usagetracker.model.AccountUsage;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The taskbar card as a picture, drawn by the real CardView over invented accounts (neutral ids, no login, no network).
 * Runs only when asked: {@code -Dscreenshots=<folder>}. The card is a Windows shell extension, so this picture is how the
 * README shows it without photographing a real taskbar.
 */
class CardScreenshotTest {

    @BeforeAll static void toolkit() {
        assumeTrue(System.getProperty("screenshots") != null, "set -Dscreenshots=<folder> to make the picture");
        var up = true;
        try { Platform.startup(() -> {}); }
        catch (IllegalStateException alreadyRunning) { }
        catch (Throwable noDisplay) { up = false; }
        assumeTrue(up, "no JavaFX toolkit here");
        Platform.setImplicitExit(false);
    }

    static <T> T onFx(java.util.function.Supplier<T> s) throws Exception {
        var f = new CompletableFuture<T>();
        Platform.runLater(() -> { try { f.complete(s.get()); } catch (Throwable t) { f.completeExceptionally(t); } });
        return f.get(30, TimeUnit.SECONDS);
    }

    static AccountUsage account(String id, boolean active, double five, int fiveLeftMin, double week, int weekLeftHours) {
        var now = OffsetDateTime.now();
        var data = new UsageData()
                .fiveHour(new Usage().utilization(five).resetsAt(now.plusMinutes(fiveLeftMin)))
                .sevenDay(new Usage().utilization(week).resetsAt(now.plusHours(weekLeftHours)));
        var c = new CCAPICredential().id(id).name(id).email(id + "@example.org").trackUsage(true).tier(1).active(true).claudecodeactive(active);
        return new AccountUsage(c, data);
    }

    @Test void theCardOverInventedAccounts() throws Exception {
        var list = new ArrayList<AccountUsage>();
        list.add(account("ann", true, 46, 118, 31, 98));
        list.add(account("bob", false, 0, 300, 12, 140));
        list.add(account("cat", false, 63, 74, 58, 55));
        list.add(account("dan", false, 18, 205, 44, 70));
        var view = new CardView();
        view.glow = false;
        view.modeLabel = () -> "auto";
        var w = 143.0;
        var h = 66.0;
        var strip = onFx(() -> {
            view.set(list);
            var box = new HBox(10);
            box.setStyle("-fx-background-color: #202020; -fx-padding: 12;");
            for (var page = 0; page < view.count(); page++) {
                view.cur = page;
                var canvas = new Canvas(w, h);
                view.draw(canvas.getGraphicsContext2D(), w, h);
                box.getChildren().add(canvas);
            }
            new Scene(box);
            var p = new SnapshotParameters();
            p.setTransform(Transform.scale(4, 4));
            p.setFill(Color.web("#202020"));
            return box.snapshot(p, null);
        });
        var out = new BufferedImage((int) strip.getWidth(), (int) strip.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var reader = strip.getPixelReader();
        for (var y = 0; y < out.getHeight(); y++) for (var x = 0; x < out.getWidth(); x++) out.setRGB(x, y, reader.getArgb(x, y));
        var file = Path.of(System.getProperty("screenshots")).resolve("taskbar-card.png");
        Files.createDirectories(file.getParent());
        ImageIO.write(out, "png", file.toFile());
        System.out.println("wrote " + file);
    }
}
