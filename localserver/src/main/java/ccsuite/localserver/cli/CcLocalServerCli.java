package ccsuite.localserver.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import ccsuite.localserver.config.*;
import ccsuite.localserver.server.*;
import ccsuite.localserver.tray.TrayUi;
import ccsuite.accounts.*;
import ccsuite.oauthswap.*;
import ccsuite.oauthclient.*;
import ccsuite.oauthswap.usage.*;
import ccsuite.base.config.CredentialsManager;

import java.awt.SystemTray;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Root command: starts the local proxy server (and, unless {@code --no-tray}, its system-tray icon). */
@Command(name = "ccls", mixinStandardHelpOptions = true, version = "ccls 1.0",
         description = "Local HTTP proxy that load-balances Claude Code OAuth accounts as a model provider.")
public final class CcLocalServerCli implements Callable<Integer> {

    @Option(names = "--port", paramLabel = "PORT", description = "Override the configured port.")
    Integer port;

    @Option(names = "--no-tray", description = "Headless: no system-tray icon.")
    boolean noTray;

    @Override public Integer call() throws Exception {
        var settingsMgr = new SettingsManager();
        var settings = settingsMgr.load();
        var effectivePort = port != null ? port : settings.port();

        var mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        var paths = ClaudeCodePaths.defaults();
        var live = new ClaudeLiveStore(paths, mapper);
        var store = new AccountStore(AccountStore.defaultStoreRoot(), mapper);
        var credsMgr = new CredentialsManager();
        var creds = credsMgr.load();
        var swap = new SwapService(live, store, creds, credsMgr.getCredentialsPath());
        var oauthClient = new OAuthUsageClient(mapper);
        var usageService = new UsageService(oauthClient, new InferenceProbeUsageClient(), live, store);

        var service = new LocalServerService(swap, store, live, usageService, oauthClient);
        service.start(effectivePort, settings.usageRefreshIntervalSecs());
        var router = service.router();
        Runtime.getRuntime().addShutdownHook(new Thread(service::stop));

        if (noTray || !SystemTray.isSupported()) {
            if (!noTray) System.err.println("System tray unavailable — running headless.");
            System.out.println("cclocalserver listening on http://127.0.0.1:" + effectivePort);
            new CountDownLatch(1).await();
            return 0;
        }

        var tray = new TrayUi(router, effectivePort, settingsMgr.settingsPath().toFile(), credsMgr.getCredentialsPath().toFile());
        var quit = tray.install();
        var trayRefresh = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "cclocalserver-tray-refresh"); t.setDaemon(true); return t;
        });
        trayRefresh.scheduleWithFixedDelay(tray::refresh, 0, 5, TimeUnit.SECONDS);
        System.out.println("cclocalserver running in the system tray on port " + effectivePort + ".");
        quit.await();
        trayRefresh.shutdownNow();
        service.stop();
        return 0;
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new CcLocalServerCli()).execute(args));
    }
}
