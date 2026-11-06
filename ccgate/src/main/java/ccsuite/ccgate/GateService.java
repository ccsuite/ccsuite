package ccsuite.ccgate;

import module java.base;
import ccsuite.quotaweights.QuotaWeights;

/** The gate as something a host switches on and off. Ledger files older than the retention are removed at each start. */
public final class GateService {

    public static final int DEFAULT_PORT = 7787, RETENTION_DAYS = 30, MAX_RETENTION_DAYS = 60;

    final Supplier<Accounts> accounts;
    GateServer server;
    Approver approver;
    Consumer<PurposeCheck.Flag> onFlag;
    Clients clients;
    Accounts open;
    int port;

    public GateService(Supplier<Accounts> accounts) {
        this.accounts = accounts;
    }

    /** Who may approve unknown callers; set before start. Without one they are refused. */
    public synchronized GateService approver(Approver approver) {
        this.approver = approver;
        return this;
    }

    /** Told about every off-purpose verdict (the tray in ccacc, the console headless); set before start. */
    public synchronized GateService onFlag(Consumer<PurposeCheck.Flag> c) {
        this.onFlag = c;
        return this;
    }

    public synchronized void start(int port, int retentionDays) throws IOException {
        if (server != null) return;
        var ledger = Ledger.standard();
        var days = Math.min(Math.max(retentionDays, 1), MAX_RETENTION_DAYS);
        ledger.prune(days);
        var spend = Spend.from(ledger, days);
        var weights = QuotaWeights.standard();
        var book = GrantBook.open();
        var s = new GateServer(port, book, new Policy(spend), new Forwarder(open = accounts.get(), ledger, spend, weights), ledger, weights).approver(approver)
                .purpose(new PurposeCheck(new HaikuJudge(open, weights), ledger).onFlag(onFlag));
        s.start();
        server = s;
        clients = new Clients(book, spend, s.activity());
        this.port = port;
    }

    public synchronized void stop() {
        if (server == null) return;
        server.stop();
        open.close();
        server = null;
        clients = null;
    }

    /** The callers and the means to rein them in; null while the gate is off. */
    public synchronized Clients clients() {
        return clients;
    }

    public synchronized boolean running() {
        return server != null;
    }

    public synchronized int port() {
        return server == null ? 0 : port;
    }
}
