package ccsuite.ccgate;

import module java.base;

/** The gate as something a host switches on and off. Ledger files older than the retention are removed at each start. */
public final class GateService {

    public static final int DEFAULT_PORT = 7787, RETENTION_DAYS = 30, MAX_RETENTION_DAYS = 60;

    final Supplier<Accounts> accounts;
    GateServer server;
    Accounts open;
    int port;

    public GateService(Supplier<Accounts> accounts) {
        this.accounts = accounts;
    }

    public synchronized void start(int port, int retentionDays) throws IOException {
        if (server != null) return;
        var ledger = Ledger.standard();
        var days = Math.min(Math.max(retentionDays, 1), MAX_RETENTION_DAYS);
        ledger.prune(days);
        var spend = Spend.from(ledger, days);
        var pricing = Pricing.standard();
        var s = new GateServer(port, GrantBook.open(), new Policy(spend), new Forwarder(open = accounts.get(), ledger, spend, pricing), ledger, pricing);
        s.start();
        server = s;
        this.port = port;
    }

    public synchronized void stop() {
        if (server == null) return;
        server.stop();
        open.close();
        server = null;
    }

    public synchronized boolean running() {
        return server != null;
    }

    public synchronized int port() {
        return server == null ? 0 : port;
    }
}
