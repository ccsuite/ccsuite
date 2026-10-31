package ccsuite.localserver.tray;

import org.slf4j.*;
import ccsuite.oauthswap.tray.*;
import ccsuite.oauthclient.*;
import ccsuite.localserver.config.SettingsManager;
import ccsuite.localserver.server.AccountRouter;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/** System-tray icon: shows the port + last-served account, and lets the user open the
 *  settings/credentials files in the default editor or quit the server. */
public final class TrayUi {

    private static final Logger log = LoggerFactory.getLogger(TrayUi.class);

    private final SystemTray tray = SystemTray.getSystemTray();
    private final TrayIcon icon;
    private final PopupMenu menu = new PopupMenu();
    private final CountDownLatch quit = new CountDownLatch(1);

    private final AccountRouter router;
    private final int port;
    private final File settingsFile;
    private final File credentialsXml;

    public TrayUi(AccountRouter router, int port, File settingsFile, File credentialsXml) {
        this.router = router; this.port = port; this.settingsFile = settingsFile; this.credentialsXml = credentialsXml;
        this.icon = new TrayIcon(
                TrayIconRenderer.render("?", null, QuotaHealth.UNKNOWN, IconFrame.ROUNDED_SQUARE), "cclocalserver — :" + port);
    }

    public CountDownLatch install() throws AWTException {
        icon.setImageAutoSize(true);
        icon.setPopupMenu(menu);
        rebuildMenu();
        tray.add(icon);
        return quit;
    }

    /** Called on a timer by the caller to refresh the icon + tooltip with the last-served account. */
    public void refresh() {
        EventQueue.invokeLater(() -> {
            var served = router.lastServed();
            var usage = router.usageFor(served);
            var health = usage == null ? QuotaHealth.UNKNOWN : usage.weeklyHealth();
            icon.setImage(TrayIconRenderer.render(monogram(served), ratioOf(usage), health, IconFrame.ROUNDED_SQUARE));
            icon.setToolTip("cclocalserver — :" + port + (served == null ? "" : " — last served: " + served));
        });
    }

    private void rebuildMenu() {
        menu.removeAll();
        var status = new MenuItem("Listening on 127.0.0.1:" + port);
        status.setEnabled(false);
        menu.add(status);
        menu.addSeparator();

        var openSettings = new MenuItem("Open settings file");
        openSettings.addActionListener(e -> openInEditor(settingsFile));
        menu.add(openSettings);

        var openCreds = new MenuItem("Open credentials file");
        openCreds.addActionListener(e -> openInEditor(credentialsXml));
        menu.add(openCreds);

        menu.addSeparator();
        var exit = new MenuItem("Quit");
        exit.addActionListener(e -> { tray.remove(icon); quit.countDown(); });
        menu.add(exit);
    }

    private void openInEditor(File file) {
        try {
            Desktop.getDesktop().open(file);
        } catch (IOException e) {
            log.warn("could not open {} in default editor: {}", file, e.toString());
        }
    }

    /** 2-letter tray-icon monogram (or "?" when nothing has been served yet). */
    private static String monogram(String id) {
        return id == null ? "?" : id.length() > 2 ? id.substring(0, 2).toUpperCase(Locale.ROOT) : id.toUpperCase(Locale.ROOT);
    }

    /** Weekly burn-rate as a 0..1+ fraction for the ring fill, or {@code null} when unknown. */
    private static Double ratioOf(AccountUsage u) {
        var avg = u == null ? null : u.sevenDayAverage();
        return avg == null ? null : avg / 100.0;
    }
}
