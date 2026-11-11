package ccsuite.ccacc;

import module java.base;
import ccsuite.usagetracker.ui.UsageWidget;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** PRP 94: a machine with only a free (tier 0) account has no paid account, and the host's usage widget must still build its window and leave the FX thread free. */
class UsageWidgetNoPaidAccountTest {

    static <T> T onFx(Supplier<T> s) throws Exception {
        var f = new CompletableFuture<T>();
        Platform.runLater(() -> { try { f.complete(s.get()); } catch (Throwable t) { f.completeExceptionally(t); } });
        return f.get(10, TimeUnit.SECONDS);   // a thread stuck on a modal is a failure here, not a hang
    }

    static List<Label> labels(Window w) {
        var out = new ArrayList<Label>();
        if (w.getScene() == null) return out;
        for (Node n : w.getScene().getRoot().lookupAll(".label")) if (n instanceof Label l) out.add(l);
        return out;
    }

    @Test void onlyAFreeAccountStillGetsTheWidgetWindowAndNoStuckThread() throws Exception {
        var home = Files.createTempDirectory("ccacc-nopaid");   // not deleted by the test: a logger of the app keeps a file open in it until the JVM ends
        MainWindowTest.toolkit();
        Files.createDirectories(home.resolve("ccsuite/base"));
        Files.writeString(home.resolve("ccsuite/base/credentials.json"),
                "{\"credentials\":[{\"id\":\"FREE1\",\"name\":\"Free one\",\"email\":\"free1@example.org\",\"orgId\":\"o\",\"key\":\"\",\"trackUsage\":true,\"active\":true,\"tier\":0}]}");
        var before = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            var w = onFx(() -> new UsageWidget().hostedBy(null, () -> null, () -> "off"));
            onFx(() -> { w.start(new Stage()); return null; });
            assertFalse(onFx(w::windowShown), "with nothing paid to show, a host does not show the empty paid window by itself");
            onFx(() -> { w.showWindow(); return null; });   // what the tray item "Usage window" does
            var shown = onFx(() -> Window.getWindows().stream().filter(x -> x instanceof Stage s && "CCAPI Usage Tracker".equals(s.getTitle())).findFirst().orElse(null));
            assertNotNull(shown, "the window exists and opens on request: start() no longer returns before building it");
            assertNotNull(onFx(shown::getScene), "and it has its scene");
            var note = false;
            for (var i = 0; i < 50 && !note; i++) {   // the first sweep runs on its own thread
                note = onFx(() -> labels(shown).stream().anyMatch(l -> l.getText().startsWith("No paid accounts")));
                if (!note) Thread.sleep(100);
            }
            assertTrue(note, "it says there is no paid account, quietly, in the window");
            assertEquals(1, onFx(() -> 1), "the FX thread answers: nothing is stuck on a dialog");
            onFx(() -> { w.shutdown(); return null; });
        } finally {
            System.setProperty("user.home", before);
        }
    }
}
