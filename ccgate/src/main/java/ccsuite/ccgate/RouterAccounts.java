package ccsuite.ccgate;

import module java.base;
import ccsuite.accounts.AccountStore;
import ccsuite.localserver.server.AccountRouter;
import ccsuite.oauthswap.SwapService;

/**
 * The accounts exactly as ccls chooses them (its AccountRouter: ranked by weekly pace, monitor-only accounts left out),
 * with one rule added: while the watcher is locked to one account, only that account serves.
 */
public final class RouterAccounts implements Accounts {

    final AccountRouter router;
    final SwapService swap;
    final AccountStore store;
    final BooleanSupplier locked;
    final Map<String, String> uuids = new ConcurrentHashMap<>();

    public RouterAccounts(AccountRouter router, SwapService swap, AccountStore store, BooleanSupplier locked) {
        this.router = router; this.swap = swap; this.store = store; this.locked = locked;
    }

    @Override
    public List<String> candidates() {
        if (!locked.getAsBoolean()) return router.rankedCandidates();
        try {
            var active = swap.detectActiveId();
            return active == null ? List.of() : List.of(active);
        } catch (IOException e) {
            return List.of();
        }
    }

    @Override
    public Optional<String> token(String id) {
        return router.tokenFor(id);
    }

    @Override
    public String uuid(String id) {
        return uuids.computeIfAbsent(id, i -> {
            try {
                return store.isRegistered(i) ? store.load(i).accountUuid() : null;
            } catch (IOException e) {
                return null;
            }
        });
    }

    @Override
    public void failed401(String id) {
        router.invalidateAfter401(id);
    }

    @Override
    public void served(String id) {
        router.markServed(id);
    }

    @Override
    public void close() {
        router.stop();
    }
}
