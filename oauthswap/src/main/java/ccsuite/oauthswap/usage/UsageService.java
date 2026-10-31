package ccsuite.oauthswap.usage;

import ccsuite.oauthclient.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.*;
import ccsuite.accounts.*;
import ccsuite.oauthswap.*;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches usage for managed accounts, mirroring claude-swap's policy: the active account
 * uses Claude Code's live token (never refreshed here — Claude owns it); inactive accounts
 * use their stored snapshot token, refreshed and re-persisted when stale or on a 401.
 */
public final class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);

    /** Manual-retry cooldown (e.g. a refresh button) — see {@link #accountUsageWithManualFallback}. */
    public static final long MANUAL_FALLBACK_COOLDOWN_MS = 3 * 60 * 1000;
    /** Auto-fallback cooldown for the Claude-Code-active account in a periodic loop. */
    public static final long AUTO_ACTIVE_FALLBACK_COOLDOWN_MS = 5 * 60 * 1000;
    /** Auto-fallback cooldown for every other account in a periodic loop. */
    public static final long AUTO_OTHER_FALLBACK_COOLDOWN_MS = 15 * 60 * 1000;
    /** Cap on the backoff {@link #accountUsagePeriodic} applies when the probe itself keeps
     *  failing — real capacity pressure on the account, not just an auxiliary endpoint being down. */
    public static final long PERIODIC_MAX_BACKOFF_MS = 30 * 60 * 1000;

    private final OAuthUsageClient client;
    private final InferenceProbeUsageClient probeClient;
    private final ClaudeLiveStore live;
    private final AccountStore store;
    // Per-account "last probe fired" clock, shared across every fallback method below and every
    // caller sharing this UsageService instance (e.g. a manual refresh click and the periodic
    // sweep both count toward the same cooldown) — see accountUsageWithFallback's hard constraint:
    // probes cost real subscription-quota tokens, so this must never be bypassed on every cycle.
    private final Map<String, Long> lastFallbackAt = new ConcurrentHashMap<>();
    // Last successful reading + consecutive-failure streak per account, both used only by
    // accountUsagePeriodic (see there).
    private final Map<String, AccountUsage> lastKnownUsage = new ConcurrentHashMap<>();
    private final Map<String, Integer> probeFailStreak = new ConcurrentHashMap<>();

    public UsageService(OAuthUsageClient client, ClaudeLiveStore live, AccountStore store) {
        this(client, null, live, store);
    }

    /** Overload that also wires the inference-probe fallback (see {@link #accountUsageViaProbe}). */
    public UsageService(OAuthUsageClient client, InferenceProbeUsageClient probeClient, ClaudeLiveStore live, AccountStore store) {
        this.client = client; this.probeClient = probeClient; this.live = live; this.store = store;
    }

    /** Usage for the live (active) login, or {@code null} if no usable token. */
    public AccountUsage activeUsage() throws IOException, InterruptedException {
        var token = OAuthCredentials.accessToken(live.readCredentials());
        if (token == null) { log.warn("no usable access token in the live Claude Code login — not logged in?"); return null; }
        return client.fetchUsage(token);
    }

    /**
     * Usage for a registered account by id. {@code active} accounts read the live token;
     * inactive ones use the snapshot, refreshing the token when stale or on a 401.
     */
    public AccountUsage accountUsage(String id, boolean active) throws IOException, InterruptedException {
        if (active) return activeUsage();
        if (!store.isRegistered(id)) return null;
        var acct = store.load(id);
        var creds = OAuthCredentials.expired(acct.credentials()) ? tryRefresh(acct, acct.credentials()) : acct.credentials();
        var token = OAuthCredentials.accessToken(creds);
        if (token == null) {
            log.warn("account {} has no usable access/refresh token in its stored snapshot — "
                    + "log into it in Claude Code and run: cswap register --id {}", id, id);
            return null;
        }
        try {
            return client.fetchUsage(token);
        } catch (UsageHttpException e) {
            if (e.status != 401) throw e;
            var token2 = OAuthCredentials.accessToken(tryRefresh(acct, acct.credentials()));
            return token2 == null ? null : client.fetchUsage(token2);
        }
    }

    /**
     * Fallback usage source for a manually-triggered retry when {@link #accountUsage} fails
     * (e.g. {@code /api/oauth/usage} 429s): fires {@link InferenceProbeUsageClient}'s minimal
     * Haiku completion and reads utilization off its response headers instead. Mirrors
     * {@code accountUsage}'s token-resolution/401-refresh policy exactly, just against a
     * different client — see that method's fetch itself costs real tokens, so callers must
     * only invoke this on demand, never from a periodic sweep.
     */
    public AccountUsage accountUsageViaProbe(String id, boolean active) throws IOException, InterruptedException {
        if (probeClient == null) throw new IllegalStateException("UsageService was built without an InferenceProbeUsageClient");
        if (active) {
            var token = OAuthCredentials.accessToken(live.readCredentials());
            return token == null ? null : probeClient.fetchUsage(token);
        }
        if (!store.isRegistered(id)) return null;
        var acct = store.load(id);
        var creds = OAuthCredentials.expired(acct.credentials()) ? tryRefresh(acct, acct.credentials()) : acct.credentials();
        var token = OAuthCredentials.accessToken(creds);
        if (token == null) return null;
        try {
            return probeClient.fetchUsage(token);
        } catch (UsageHttpException e) {
            if (e.status != 401) throw e;
            var token2 = OAuthCredentials.accessToken(tryRefresh(acct, acct.credentials()));
            return token2 == null ? null : probeClient.fetchUsage(token2);
        }
    }

    /**
     * {@link #accountUsage} with an automatic inference-probe fallback on failure, gated by
     * the periodic-loop cooldown tiers: {@link #AUTO_ACTIVE_FALLBACK_COOLDOWN_MS} for the
     * Claude-Code-active account, {@link #AUTO_OTHER_FALLBACK_COOLDOWN_MS} for every other
     * one. On cooldown (or when no probe client was wired), the original failure is rethrown
     * unchanged — same throws contract as {@code accountUsage}, just with a rate-limited retry
     * baked in. Intended for long-running periodic callers (cswap watch, cclocalserver's
     * refresh loop) as a drop-in replacement for {@code accountUsage}.
     */
    public AccountUsage accountUsageWithFallback(String id, boolean active) throws IOException, InterruptedException {
        try {
            return accountUsage(id, active);
        } catch (IOException primary) {
            var cooldownMs = active ? AUTO_ACTIVE_FALLBACK_COOLDOWN_MS : AUTO_OTHER_FALLBACK_COOLDOWN_MS;
            var probed = probeFallbackIfDue(id, active, cooldownMs);
            if (probed != null) return probed;
            throw primary;
        }
    }

    /**
     * {@link #accountUsage} with an automatic inference-probe fallback gated by the shorter
     * {@link #MANUAL_FALLBACK_COOLDOWN_MS} tier — for a human-triggered retry (e.g. a refresh
     * button), not a periodic sweep. Shares the same per-account cooldown clock as {@link
     * #accountUsageWithFallback}, so a recent automatic attempt also counts against a manual
     * retry and vice versa.
     */
    public AccountUsage accountUsageWithManualFallback(String id, boolean active) throws IOException, InterruptedException {
        try {
            return accountUsage(id, active);
        } catch (IOException primary) {
            var probed = probeFallbackIfDue(id, active, MANUAL_FALLBACK_COOLDOWN_MS);
            if (probed != null) return probed;
            throw primary;
        }
    }

    /**
     * Cooldown-gated inference-probe fallback for callers whose own primary usage fetch is
     * outside this class entirely (e.g. usage_tracker's session-cookie-based CCAPI route) and
     * has already failed. Returns {@code null} — without touching the network — when {@code id}
     * was already probed within {@code cooldownMs} (this is the real token-cost control: a
     * probe fires at most once per cooldown window regardless of how many callers/threads share
     * this instance), or when no probe client was wired into this service. Otherwise fires the
     * probe and records the attempt time before returning (or throwing, if the probe itself
     * fails — that exception propagates to the caller as the final outcome).
     */
    public AccountUsage probeFallbackIfDue(String id, boolean active, long cooldownMs) throws IOException, InterruptedException {
        if (probeClient == null) return null;
        var now = System.currentTimeMillis();
        var last = lastFallbackAt.get(id);
        if (last != null && now - last < cooldownMs) {
            log.debug("{}: probe fallback on cooldown ({}s left)", id, (cooldownMs - (now - last)) / 1000);
            return null;
        }
        lastFallbackAt.put(id, now);
        log.info("{}: trying inference-probe fallback (~9 real tokens)", id);
        return accountUsageViaProbe(id, active);
    }

    /**
     * Usage for a long-running periodic poller (cswap watch, cclocalserver) — sourced entirely
     * from the inference-probe's response-header route, never {@link OAuthUsageClient}'s
     * {@code /api/oauth/usage} GET, which 429s aggressively under frequent polling regardless of
     * which account asks (see {@link InferenceProbeUsageClient}). Gated by the same cooldown
     * tiers as the automatic fallback ({@link #AUTO_ACTIVE_FALLBACK_COOLDOWN_MS}/{@link
     * #AUTO_OTHER_FALLBACK_COOLDOWN_MS}), widened with exponential backoff (capped at {@link
     * #PERIODIC_MAX_BACKOFF_MS}) on consecutive probe failures — the probe rides the same real
     * Messages API your own actual coding traffic (and any other poller sharing this account)
     * also depends on, so a caller ticking every minute must still ease off when that's under
     * pressure too, not retry on the same fixed cadence forever. Between due probes this makes
     * no network call at all, returning the last successful reading ({@code null} before the
     * first one). Never throws: a failed probe attempt is logged and the last-known reading
     * returned instead.
     */
    public AccountUsage accountUsagePeriodic(String id, boolean active) {
        if (probeClient == null) throw new IllegalStateException("UsageService was built without an InferenceProbeUsageClient");
        var baseCooldown = active ? AUTO_ACTIVE_FALLBACK_COOLDOWN_MS : AUTO_OTHER_FALLBACK_COOLDOWN_MS;
        var streak = probeFailStreak.getOrDefault(id, 0);
        var cooldownMs = Math.min(PERIODIC_MAX_BACKOFF_MS, baseCooldown << Math.min(streak, 10));
        var now = System.currentTimeMillis();
        var last = lastFallbackAt.get(id);
        if (last != null && now - last < cooldownMs) return lastKnownUsage.get(id);

        lastFallbackAt.put(id, now);
        try {
            var u = accountUsageViaProbe(id, active);
            lastKnownUsage.put(id, u);
            probeFailStreak.remove(id);
            return u;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return lastKnownUsage.get(id);
        } catch (IOException e) {
            var newStreak = probeFailStreak.merge(id, 1, Integer::sum);
            var nextMs = Math.min(PERIODIC_MAX_BACKOFF_MS, baseCooldown << Math.min(newStreak, 10));
            log.warn("{}: periodic probe failed ({}) — next attempt in {}s (streak {})",
                    id, UsageErrors.terse(e), nextMs / 1000, newStreak);
            return lastKnownUsage.get(id);
        }
    }

    private JsonNode tryRefresh(ClaudeCodeAccount acct, JsonNode creds) {
        var refreshed = client.refresh(creds);
        if (refreshed == null) return creds;
        try {
            store.save(acct.credentials(refreshed));
            log.info("Refreshed + re-stored OAuth token for inactive account {}", acct.id());
        } catch (IOException e) {
            log.warn("Refreshed token for {} but failed to persist: {}", acct.id(), e.toString());
        }
        return refreshed;
    }
}
