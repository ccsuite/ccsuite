package ccsuite.oauthswap.cli;

import picocli.CommandLine.*;
import ccsuite.oauthswap.watch.*;

import java.awt.SystemTray;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

@Command(name = "watch", mixinStandardHelpOptions = true,
         description = "Watch quota in the system tray; prompt at the soft limit, auto-switch when depleted.")
public final class WatchCmd implements Callable<Integer> {

    @ParentCommand ClaudeSwapCli parent;

    @Option(names = "--interval", paramLabel = "SEC", description = "Base poll interval (default 60).")
    int interval = 60;
    @Option(names = "--max-backoff", paramLabel = "SEC", description = "Max poll interval after error backoff (default 300).")
    int maxBackoff = 300;
    @Option(names = "--limit", paramLabel = "PCT", description = "Override soft limit percent (else per-account claudecodelimit).")
    double limit = -1;
    @Option(names = "--no-auto-switch", description = "Never auto-switch on depletion; prompt only.")
    boolean noAutoSwitch;
    @Option(names = "--no-rebalance", description = "Never proactively rebalance toward the best-paced account when nothing is urgent.")
    boolean noRebalance;
    @Option(names = "--rebalance-min-improvement", paramLabel = "RATIO",
            description = "Minimum normalized-ratio improvement before rebalancing switches (default 0.01).")
    double rebalanceMinImprovement = 0.01;
    @Option(names = "--no-tray", description = "Headless: log to console instead of using the system tray.")
    boolean noTray;
    @Option(names = "--once", description = "Run a single poll, print the decision, then exit (never switches).")
    boolean once;

    @Override public Integer call() {
        try {
            var svc = parent.service();
            var usage = parent.usageService(svc);
            var cfg = new WatchConfig(interval, maxBackoff, 100.0, 5.0, limit, !noAutoSwitch, 600,
                    !noRebalance, rebalanceMinImprovement, 50.0, 24);
            var planner = new SwitchPlanner(cfg.depletedAtPct(), cfg.minTargetHeadroom());
            var rebalancer = BalancePlanner.of(cfg);

            if (once) {
                new UsageWatcher(usage, svc, planner, rebalancer, cfg, new ConsoleWatchListener()).runOnce();
                return 0;
            }
            if (noTray || !SystemTray.isSupported()) {
                if (!noTray) System.err.println("System tray unavailable — running headless.");
                var w = new UsageWatcher(usage, svc, planner, rebalancer, cfg, new ConsoleWatchListener());
                Runtime.getRuntime().addShutdownHook(new Thread(w::stop));
                w.start();
                new CountDownLatch(1).await();
                return 0;
            }
            var tray = new TrayUi();
            var w = new UsageWatcher(usage, svc, planner, rebalancer, cfg, tray);
            var quit = tray.install(w);
            w.start();
            System.out.println("cswap watch running in the system tray. Right-click the icon to switch or quit.");
            quit.await();
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e); // e.toString() keeps the type even when message is null
            return 1;
        }
    }
}
