package ccsuite.localserver.server;

import ccsuite.accounts.AccountStore;
import ccsuite.accounts.ClaudeLiveStore;
import ccsuite.oauthclient.OAuthUsageClient;
import ccsuite.oauthswap.SwapService;
import ccsuite.oauthswap.usage.UsageService;

import java.io.IOException;

/**
 * The local proxy as something a host can switch on and off: each start builds a fresh
 * {@link AccountRouter} (its refresh thread cannot be restarted) and {@link ProxyServer}.
 * The usage service is the host's own, so a poll by another part of the process and the
 * router's refresh share one per-account cooldown instead of probing twice.
 */
public final class LocalServerService {

    private final SwapService swap;
    private final AccountStore store;
    private final ClaudeLiveStore live;
    private final UsageService usage;
    private final OAuthUsageClient oauth;

    private AccountRouter router;
    private ProxyServer server;
    private int port;

    public LocalServerService(SwapService swap, AccountStore store, ClaudeLiveStore live, UsageService usage, OAuthUsageClient oauth) {
        this.swap = swap; this.store = store; this.live = live; this.usage = usage; this.oauth = oauth;
    }

    /** Starts listening on 127.0.0.1:{@code port}; does nothing when already running. */
    public synchronized void start(int port, int refreshIntervalSec) throws IOException {
        if (server != null) return;
        var r = new AccountRouter(swap, store, live, usage, oauth);
        var s = new ProxyServer(r, port);
        s.start();
        r.start(refreshIntervalSec);
        router = r; server = s; this.port = port;
    }

    public synchronized void stop() {
        if (server == null) return;
        server.stop();
        router.stop();
        server = null;
        router = null;
    }

    public synchronized boolean running() { return server != null; }

    /** The port while running, else 0. */
    public synchronized int port() { return server == null ? 0 : port; }

    /** The router of the running server, or {@code null} while stopped. */
    public synchronized AccountRouter router() { return router; }
}
