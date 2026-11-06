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
    FreeGauges freeGauges;
    FreeUsageUi freeUi;

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
            bridge.readings = ReadingsLog.standard();
            parts = new Parts(core, store, store.load(), bridge,
                    new LocalServerService(core.swap, core.store, core.live, core.usage, core.oauth),
                    GateRoute.service(core).approver(new FxApprover()).onFlag(this::flagged));
            var accounts = new Accounts(core.credentials, core.creds, () -> { var w = parts.watcher; if (w != null) w.pollNow(); });
            var commands = new Commands(accounts, store, parts, core.swap);
            control = ControlServer.start(Control.defaultFile(), args -> args.get(0).equals("free") ? freeReply(args)
                    : LaneCommands.handles(args) ? new LaneCommands(store, parts.lanes).run(args) : commands.run(args));
            var items = new TrayItems(parts, bridge, this::quit);
            var window = new MainWindow(parts, bridge, COLOR, items::status);
            items.openWindow = window::toggle;
            var free = FreeGauges.standard(() -> core.creds.credentials());
            freeGauges = free;
            var freeWindow = new FreeWindow(free, FreeSummary.COLOR);
            items.openFree = freeWindow::toggle;
            parts.lanes = new LaneHost(core, store, parts.settings, free);
            items.openLanes = new LanesWindow(parts.lanes, COLOR)::toggle;
            items.openParts = new PartsWindow(items, COLOR)::toggle;
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
            items.hasFree = () -> !getParameters().getRaw().contains("--no-free") && !FreeGauges.free(free.accounts.get()).isEmpty();
            if (!getParameters().getRaw().contains("--no-free")) startFreeUsage(free, freeWindow);
            else if (ccsuite.usagetracker.ui.UsageWidget.WINDOWS) ccsuite.usagetracker.taskbar.SlotLayout.freeCard(false);   // the band shrinks back to the usage card alone
            IdleTrim.start();
            parts.startConfigured(getParameters().getRaw());
            if (!getParameters().getRaw().contains("--no-free")) freeGauges.start(java.time.Duration.ofMinutes(5));
        } catch (Exception e) {
            System.err.println("ccacc could not start: " + e);
            e.printStackTrace();
            quit();
        }
    }

    /** {@code ccacc free ...} asked of the running app: answered from its own gauge table. */
    Control.Reply freeReply(java.util.List<String> args) {
        var g = freeGauges;
        return g == null ? Control.Reply.error("the free accounts are not read yet") : FreeCli.serve(args, g, parts.settings.lanes());
    }

    /** The free accounts' usage, apart from the paid ones, exists only while a free account does: see {@link FreeUsageUi}. No account ever reaches a menu. */
    void startFreeUsage(FreeGauges free, FreeWindow freeWindow) {
        var ui = new FreeUsageUi(free, parts.settings.lanes(), freeWindow, ccsuite.usagetracker.ui.UsageWidget.WINDOWS);
        freeUi = ui;
        ui.sync();
        free.onChange = ui::changed;
        var again = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(30), e -> ui.sync()));
        again.setCycleCount(javafx.animation.Animation.INDEFINITE);
        again.play();
    }

    /** An off-purpose verdict from the gate's purpose check: a tray message, so the owner sees it and decides. */
    void flagged(ccsuite.ccgate.PurposeCheck.Flag f) {
        var t = tray;
        if (t != null) t.warning("ccgate: outside a grant's purpose", "Grant '" + f.grant() + "': " + f.reason());
        System.err.println("ccgate: purpose flag on grant " + f.grant() + " (session " + f.session() + "): " + f.reason());
    }

    void quit() {
        if (control != null) control.close();
        if (follower != null) follower.stop();
        if (freeGauges != null) freeGauges.stop();
        if (freeUi != null && freeUi.shown) freeUi.hide();
        if (parts != null) parts.stopAll();
        if (tray != null) tray.remove();
        Platform.exit();
        System.exit(0);
    }
}
