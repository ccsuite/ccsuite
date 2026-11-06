package ccsuite.localserver.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.*;
import ccsuite.accounts.*;
import ccsuite.oauthswap.*;
import ccsuite.oauthclient.*;
import ccsuite.oauthswap.usage.*;
import ccsuite.base.model.CCAPICredential;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * Keeps a periodically-refreshed weekly-usage snapshot and, per incoming request, hands out
 * an ordered candidate list (best normalized pace first, via {@link RatioBalancer}) plus the
 * live access token for whichever candidate {@link ProxyServer} decides to try — including the
 * currently-active account, whose token is always read live (never refreshed here — Claude Code
 * owns it), matching {@link UsageService}'s own policy.
 */
public final class AccountRouter {

    private static final Logger log = LoggerFactory.getLogger(AccountRouter.class);
    private static final double MIN_HEADROOM = 5.0;

    private final SwapService swap;
    private final AccountStore store;
    private final ClaudeLiveStore live;
    private final UsageService usageService;
    private final OAuthUsageClient oauthClient;

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "cclocalserver-usage-refresh"); t.setDaemon(true); return t;
    });

    private volatile Map<String, AccountUsage> usages = Map.of();
    private volatile String lastServed;

    public AccountRouter(SwapService swap, AccountStore store, ClaudeLiveStore live,
                          UsageService usageService, OAuthUsageClient oauthClient) {
        this.swap = swap; this.store = store; this.live = live;
        this.usageService = usageService; this.oauthClient = oauthClient;
    }

    public void start(int refreshIntervalSec) {
        exec.scheduleWithFixedDelay(this::refresh, 0, refreshIntervalSec, TimeUnit.SECONDS);
    }

    public void stop() { exec.shutdownNow(); }

    /** Eligible accounts ranked best-normalized-pace first; empty when none have headroom. */
    public List<String> rankedCandidates() {
        return RatioBalancer.rank(servableUsages(), limitPercents(), MIN_HEADROOM);
    }

    /** Live access token for {@code id} — live credentials if it's the active account, else its snapshot (refreshed if stale). */
    public Optional<String> tokenFor(String id) {
        try {
            var activeId = swap.detectActiveId();
            var creds = id.equals(activeId) ? live.readCredentials() : snapshotCreds(id);
            return Optional.ofNullable(OAuthCredentials.accessToken(creds));
        } catch (Exception e) {
            log.debug("could not resolve token for {}: {}", id, e.toString());
            return Optional.empty();
        }
    }

    /** Force a refresh of {@code id}'s stored token — called after that candidate's request 401s. */
    public void invalidateAfter401(String id) {
        try {
            var activeId = swap.detectActiveId();
            if (id.equals(activeId) || !store.isRegistered(id)) return;
            refreshAndPersist(store.load(id));
        } catch (Exception e) {
            log.debug("401-triggered refresh failed for {}: {}", id, e.toString());
        }
    }

    /** The lowest weekly pace ({@link AccountUsage#sevenDayAverage()}, 100 = on pace to use the week exactly) over the accounts the proxy may serve from; empty when none is known. */
    public OptionalDouble weeklyPace() {
        return servableUsages().values().stream().filter(Objects::nonNull).map(AccountUsage::sevenDayAverage).filter(Objects::nonNull).mapToDouble(Double::doubleValue).min();
    }

    public String lastServed() { return lastServed; }
    public void markServed(String id) { lastServed = id; }

    /** Latest cached usage for {@code id} (from the background refresh loop), or {@code null} if unknown. */
    public AccountUsage usageFor(String id) { return id == null ? null : usages.get(id); }

    private JsonNode snapshotCreds(String id) throws Exception {
        if (!store.isRegistered(id)) return null;
        var acct = store.load(id);
        var creds = acct.credentials();
        return OAuthCredentials.expired(creds) ? refreshAndPersist(acct) : creds;
    }

    private JsonNode refreshAndPersist(ClaudeCodeAccount acct) {
        var refreshed = oauthClient.refresh(acct.credentials());
        if (refreshed == null) return acct.credentials();
        try {
            store.save(acct.credentials(refreshed));
            log.info("Refreshed + re-stored OAuth token for {}", acct.id());
        } catch (Exception e) {
            log.warn("Refreshed token for {} but failed to persist: {}", acct.id(), e.toString());
        }
        return refreshed;
    }

    private void refresh() {
        try {
            var activeId = swap.detectActiveId();
            var map = new LinkedHashMap<String, AccountUsage>();
            for (var c : swap.eligible()) {
                var id = c.id();
                var isActive = id.equals(activeId);
                if (!isActive && !store.isRegistered(id)) continue;
                map.put(id, usageService.accountUsagePeriodic(id, isActive));
            }
            usages = map;
            log.debug("usage refresh: {} accounts, ranked {}", map.size(), rankedCandidates());
        } catch (Exception e) {
            log.warn("usage refresh cycle failed: {}", e.toString());
        }
    }

    /** The usages of the accounts the proxy may serve from: monitor-only accounts are watched, never routed to. */
    private Map<String, AccountUsage> servableUsages() {
        var out = new LinkedHashMap<String, AccountUsage>();
        usages.forEach((id, u) -> { if (swap.isServable(id)) out.put(id, u); });
        return out;
    }

    private Map<String, Double> limitPercents() {
        return swap.eligible().stream().collect(Collectors.toMap(CCAPICredential::id, CCAPICredential::claudeCodeLimitPercent));
    }
}
