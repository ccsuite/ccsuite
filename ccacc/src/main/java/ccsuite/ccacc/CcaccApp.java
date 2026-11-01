package ccsuite.ccacc;

import ccsuite.localserver.server.LocalServerService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import leasegate.tray.FxTray;
import leasegate.tray.TrayMenu;

/**
 * One resident app for the Claude Code line: a tray icon whose menu is drawn by JavaFX, and behind it the
 * account watcher, the usage widget with its taskbar card and the local proxy, each switchable from the menu.
 * Flags for one run: {@code --no-watch}, {@code --no-widget}, {@code --no-server}.
 */
public final class CcaccApp extends Application {

    static final String COLOR = "#d97757";

    Parts parts;
    FxTray tray;
    SingleInstance only;
    CredentialsFollower follower;
    ControlServer control;

    @Override public void start(Stage unused) {
        Platform.setImplicitExit(false);
        try {
            only = SingleInstance.acquire(SingleInstance.defaultFile());
            if (only == null) {
                System.err.println("ccacc is already running (its icon is in the tray); this one stops.");
                Platform.exit();
                System.exit(0);
                return;
            }
            var core = new Core();
            follower = new CredentialsFollower(core.credentials, core.creds, () -> { });
            follower.start();
            var store = new CcaccSettingsStore();
            var bridge = new WatchBridge();
            parts = new Parts(core, store, store.load(), bridge,
                    new LocalServerService(core.swap, core.store, core.live, core.usage, core.oauth));
            var accounts = new Accounts(core.credentials, core.creds, () -> { var w = parts.watcher; if (w != null) w.pollNow(); });
            control = ControlServer.start(Control.defaultFile(), new Commands(accounts, store, parts, core.swap)::run);
            var items = new TrayItems(parts, bridge, this::quit);
            var window = new MainWindow(parts, bridge, COLOR, items::status);
            items.openWindow = window::toggle;
            bridge.onChange = window::redrawIfShowing;
            try {
                var menu = new TrayMenu(FxImages.of(CcaccIcon.draw(64)), "ccacc", COLOR, items::status, items::build);
                tray = FxTray.install(CcaccIcon.draw(32), "ccacc", menu, window::toggle);
                bridge.tray = tray;
            } catch (Throwable noTray) {
                // A desktop without a tray (or one where the tray cannot be made, as may happen on a Mac): the app still runs,
                // and its window is the way in, shown now.
                System.err.println("ccacc: no tray icon here (" + noTray + "); showing the window instead.");
                window.show();
            }
            IdleTrim.start();
            parts.startConfigured(getParameters().getRaw());
        } catch (Exception e) {
            System.err.println("ccacc could not start: " + e);
            e.printStackTrace();
            quit();
        }
    }

    void quit() {
        if (control != null) control.close();
        if (follower != null) follower.stop();
        if (parts != null) parts.stopAll();
        if (tray != null) tray.remove();
        Platform.exit();
        System.exit(0);
    }
}
