package ccsuite.ccacc;

import ccsuite.ccgate.Approval;
import ccsuite.ccgate.ApprovalRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javax.imageio.ImageIO;
import leasegate.frame.Brand;
import leasegate.frame.GateIcons;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ApprovalWindowTest {

    static final Brand BRAND = new Brand("ccacc gate", CcaccApp.COLOR, new GateIcons(CcaccApp.class, "ccacc"));

    @BeforeAll static void toolkit() {
        var up = true;
        try { Platform.startup(() -> {}); }
        catch (IllegalStateException alreadyRunning) { }
        catch (Throwable noDisplay) { up = false; }
        assumeTrue(up, "no JavaFX toolkit here");
        if (up) Platform.setImplicitExit(false);
    }

    static <T> T onFx(java.util.function.Supplier<T> s) throws Exception {
        var f = new CompletableFuture<T>();
        Platform.runLater(() -> { try { f.complete(s.get()); } catch (Throwable t) { f.completeExceptionally(t); } });
        return f.get(10, TimeUnit.SECONDS);
    }

    static ApprovalRequest request(String family) {
        return new ApprovalRequest("ab".repeat(32), "example-tool/1.0", null, "claude-" + family + "-5-5", family, Instant.now());
    }

    @Test void nothingIsGrantedBeforeTheWindowHasSettled() throws Exception {
        var answer = new CompletableFuture<Optional<Approval>>();
        var w = onFx(() -> { var x = new ApprovalWindow(request("haiku"), BRAND, answer::complete); x.show(); return x; });
        onFx(() -> { w.approve(); return null; });
        assertFalse(answer.isDone(), "F2 in the first two seconds must do nothing");
        Thread.sleep(2600);
        onFx(() -> { w.approve(); return null; });
        var a = answer.get(2, TimeUnit.SECONDS).orElseThrow();
        assertEquals(java.util.List.of("haiku"), a.models(), "the proposal for a Haiku caller is Haiku only");
        assertEquals(7, a.days());
    }

    @Test void theProposalStartsAtTheModelTheCallerAskedFor() throws Exception {
        var answer = new CompletableFuture<Optional<Approval>>();
        var w = onFx(() -> { var x = new ApprovalWindow(request("sonnet"), BRAND, answer::complete); x.show(); return x; });
        Thread.sleep(2600);
        onFx(() -> { w.length.move(1); w.approve(); return null; });
        var a = answer.get(2, TimeUnit.SECONDS).orElseThrow();
        assertEquals(java.util.List.of("haiku", "sonnet"), a.models());
        assertEquals(30, a.days());
    }

    @Test void denyAndCloseAreDeclines() throws Exception {
        var answer = new CompletableFuture<Optional<Approval>>();
        var w = onFx(() -> { var x = new ApprovalWindow(request("opus"), BRAND, answer::complete); x.show(); return x; });
        onFx(() -> { w.withdraw(); return null; });
        assertTrue(answer.get(2, TimeUnit.SECONDS).isEmpty());
    }

    /** {@code -Dapproval.shot=<file.png>} writes the window as a picture, for looking at it. */
    @Test void picture() throws Exception {
        var file = System.getProperty("approval.shot");
        assumeTrue(file != null, "no picture asked for");
        var w = onFx(() -> { var x = new ApprovalWindow(request("sonnet"), BRAND, r -> { }); x.show(); return x; });
        Thread.sleep(3000);
        var image = onFx(() -> w.stage.getScene().getRoot().snapshot(null, null));
        var out = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var reader = image.getPixelReader();
        for (var y = 0; y < out.getHeight(); y++)
            for (var x = 0; x < out.getWidth(); x++) out.setRGB(x, y, reader.getArgb(x, y));
        ImageIO.write(out, "png", new java.io.File(file));
        onFx(() -> { w.withdraw(); return null; });
    }
}
