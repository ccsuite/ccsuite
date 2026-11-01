package ccsuite.ccacc;

import module java.base;
import ccsuite.ccgate.*;
import ccsuite.localserver.server.AccountRouter;
import ccsuite.oauthswap.watch.WatchMode;

/** "ccacc gate ..." and "ccacc ledger ...": grants, keys and reports; "ccacc gate serve" runs the gate alone, no tray. */
final class GateRoute {

    static final int USAGE_REFRESH_SECS = 300;

    static int run(String[] args) {
        try {
            var list = List.of(args);
            if (list.size() >= 2 && list.get(0).equals("gate") && list.get(1).equals("serve")) return serve(new Flags(list.subList(2, list.size())));
            var out = GateCli.run(list.isEmpty() ? "" : list.get(0), list.size() > 1 ? list.subList(1, list.size()) : List.of());
            if (!out.isEmpty()) System.out.println(out);
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    static int serve(Flags f) throws Exception {
        var port = Integer.parseInt(f.get("port", String.valueOf(GateService.DEFAULT_PORT)));
        var gate = service(new Core());
        gate.start(port, Integer.parseInt(f.get("keep-days", String.valueOf(GateService.RETENTION_DAYS))));
        System.out.println("ccgate on http://127.0.0.1:" + port + " (ledger " + GateHome.ledger() + "); Ctrl+C stops");
        Thread.currentThread().join();
        return 0;
    }

    /** The gate over the same accounts as everything else in ccacc; locked watcher = only the active account serves. */
    static GateService service(Core core) {
        return new GateService(() -> {
            var router = new AccountRouter(core.swap, core.store, core.live, core.usage, core.oauth);
            router.start(USAGE_REFRESH_SECS);
            return new RouterAccounts(router, core.swap, core.store, () -> new CcaccSettingsStore().load().watchMode() == WatchMode.LOCKED);
        });
    }

    private GateRoute() { }
}
