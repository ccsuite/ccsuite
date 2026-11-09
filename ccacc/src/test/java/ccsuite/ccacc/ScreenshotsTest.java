package ccsuite.ccacc;

import ccsuite.base.config.CredentialsManager;
import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.CCAPIsCredentials;
import ccsuite.localserver.server.LocalServerService;
import ccsuite.oauthclient.AccountUsage;
import ccsuite.oauthswap.watch.UsageWatcher;
import ccsuite.oauthswap.watch.WatchConfig;
import ccsuite.oauthswap.watch.WatchMode;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.TabPane;
import javafx.scene.transform.Transform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Makes the pictures for the README and the announcement posts from the real windows over invented data: neutral ids,
 * example.org addresses, no real login, no file of the user's, no network. It only runs when asked:
 * {@code mvn -o test -pl ccacc -am -Dtest=ScreenshotsTest -Dscreenshots=<folder> -Dsurefire.failIfNoSpecifiedTests=false}.
 * Re-running reproduces them, so a changed look is one command.
 */
class ScreenshotsTest {

    static final double SCALE = 2;

    @BeforeAll static void toolkit() {
        assumeTrue(System.getProperty("screenshots") != null, "set -Dscreenshots=<folder> to make the pictures");
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

    static AccountUsage usage(double five, int fiveLeftMin, double week, int weekLeftHours) {
        var now = OffsetDateTime.now();
        var weekElapsed = (7 * 24 - weekLeftHours) * 100.0 / (7 * 24);
        return new AccountUsage(five, now.plusMinutes(fiveLeftMin).toString(), week, now.plusHours(weekLeftHours).toString(), weekElapsed);
    }

    static CCAPICredential account(String id, String name, String limit, boolean monitorOnly) {
        return new CCAPICredential().id(id).name(name).email(id + "@example.org").trackUsage(true).tier(1).active(true)
                .claudecodelimit(limit).watchOnly(monitorOnly).claudecodeactive(id.equals("ann"));
    }

    /** One window, one file: the scene as it is drawn, at twice the size so the text is sharp on a high-density screen. */
    static void save(Node root, Scene scene, Path file) throws Exception {
        var image = onFx(() -> {
            var p = new SnapshotParameters();
            p.setTransform(Transform.scale(SCALE, SCALE));
            return scene.getRoot().snapshot(p, null);
        });
        var w = (int) image.getWidth();
        var h = (int) image.getHeight();
        var out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var reader = image.getPixelReader();
        for (var y = 0; y < h; y++) for (var x = 0; x < w; x++) out.setRGB(x, y, reader.getArgb(x, y));
        Files.createDirectories(file.getParent());
        ImageIO.write(out, "png", file.toFile());
        System.out.println("wrote " + file);
    }

    @Test void theWindowsOverInventedData(@TempDir Path dir) throws Exception {
        var folder = Path.of(System.getProperty("screenshots"));
        var bridge = new WatchBridge();
        bridge.activeId = "ann";
        var usages = new LinkedHashMap<String, AccountUsage>();
        usages.put("ann", usage(46, 118, 31, 98));
        usages.put("bob", usage(0, 300, 12, 140));
        usages.put("cat", usage(63, 74, 58, 55));
        usages.put("dan", usage(18, 205, 44, 70));
        usages.put("eve", usage(5, 270, 22, 120));
        bridge.usages = usages;

        var creds = new CCAPIsCredentials();
        creds.credentials().add(account("ann", "Ann", "95%", false));
        creds.credentials().add(account("bob", "Bob", "95%", false));
        creds.credentials().add(account("cat", "Cat", "90%", false));
        creds.credentials().add(account("dan", "Dan", "95%", false));
        creds.credentials().add(account("eve", "Eve (reserve)", "99%", true));
        var manager = new CredentialsManager(dir.resolve("credentials.json"));
        manager.save(creds);

        var store = new CcaccSettingsStore(dir.resolve("settings.json"));
        var settings = new CcaccSettings();
        var parts = new Parts(null, store, settings, bridge, new LocalServerService(null, null, null, null, null), new ccsuite.ccgate.GateService(() -> null));
        parts.watcher = new UsageWatcher(null, null, null, null, WatchConfig.defaults(), bridge);
        parts.watcher.mode(WatchMode.AUTO);

        var main = onFx(() -> new MainWindow(parts, bridge, CcaccApp.COLOR, () -> "ann  5h 46%  week 31% (avg 74%)  ·  auto", MainWindowTest.pane(), new TrayItems(parts, bridge, () -> { })));
        onFx(() -> { main.stage.show(); main.sync(); return null; });
        var scene = onFx(() -> main.stage.getScene());
        save(scene.getRoot(), scene, folder.resolve("paid-accounts.png"));
        onFx(() -> { main.tabs.getSelectionModel().select(main.serversTab); main.sync(); return null; });
        save(scene.getRoot(), scene, folder.resolve("servers.png"));
        onFx(() -> { main.stage.hide(); return null; });

        var manage = onFx(() -> new AccountsPane(parts, new Accounts(manager, creds, () -> { })));
        var manageStage = onFx(() -> { var st = new javafx.stage.Stage(); st.setScene(new javafx.scene.Scene(manage, 900, 560)); manage.start(); manage.list.getSelectionModel().select(2); st.show(); return st; });
        var manageScene = onFx(() -> manageStage.getScene());
        save(manageScene.getRoot(), manageScene, folder.resolve("manage-accounts.png"));
        onFx(() -> { manageStage.hide(); return null; });
    }
}
