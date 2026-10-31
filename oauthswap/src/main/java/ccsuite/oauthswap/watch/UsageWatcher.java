package ccsuite.oauthswap.watch;

import org.slf4j.*;
import ccsuite.oauthswap.SwapService;
import ccsuite.oauthclient.*;
import ccsuite.oauthswap.usage.*;
import ccsuite.base.model.CCAPICredential;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * Single-threaded poll loop. Each tick fetches usage for every registered eligible account
 * (via {@link UsageService#accountUsagePeriodic}, which owns its own per-account probe cadence
 * and backoff — a rate-limited/stale reading never fails this tick, it just returns the last
 * known value), asks {@link SwitchPlanner} for a {@link Decision}, and acts: prompt (via the
 * listener) at the soft limit, auto-switch when the active account is depleted. This loop's own
 * {@code errorStreak}/backoff is driven only by genuinely unexpected failures (e.g. identity/store
 * lookups), not by usage-check rate-limiting. All switches — scheduled or user-requested from the
 * tray — run on this one thread, so file writes never race.
 */
public final class UsageWatcher {

    private static final Logger log = LoggerFactory.getLogger(UsageWatcher.class);

    private final UsageService usage;
    private final SwapService swap;
    private final SwitchPlanner planner;
    private final RebalancePlanner rebalancer;
    private final WatchConfig cfg;
    private final WatchListener listener;

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "cswap-watch"); t.setDaemon(true); return t;
    });

    private volatile WatchMode mode = WatchMode.AUTO;
    private volatile String lastActiveId;
    private final ManualHold hold = new ManualHold();
    private int errorStreak = 0;
    private ScheduledFuture<?> nextTick;
    private String lastPromptId;
    private long lastPromptAt;
    private Map<String, AccountUsage> lastUsages = Map.of();

    public UsageWatcher(UsageService usage, SwapService swap, SwitchPlanner planner, RebalancePlanner rebalancer,
                        WatchConfig cfg, WatchListener listener) {
        this.usage = usage; this.swap = swap; this.planner = planner; this.rebalancer = rebalancer;
        this.cfg = cfg; this.listener = listener;
    }

    public void start()                 { exec.execute(this::tick); }
    public void stop()                  { exec.shutdownNow(); }
    public void pollNow()               { exec.execute(this::tick); }
    public void requestSwitch(String id){ exec.execute(() -> { doSwitch(id, false); tick(); }); }

    /**
     * A switch the user asked for, done on the watcher's own thread (so it cannot race a poll) and waited for; {@code id}
     * null rotates to the next account. Holds rebalancing for a while, as any switch of the user's does.
     */
    public ccsuite.oauthswap.SwitchResult switchNow(String id) throws Exception {
        var done = exec.submit(() -> {
            var r = id == null ? swap.switchNext() : swap.switchTo(id);
            hold.manual(r.toId(), System.currentTimeMillis());
            lastPromptId = null;
            listener.onSwitched(r, false);
            return r;
        });
        try {
            var r = done.get(120, TimeUnit.SECONDS);
            pollNow();
            return r;
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception c ? c : e;
        }
    }

    public WatchMode mode() { return mode; }

    /** Takes effect from the next tick; leaving PAUSED polls at once. */
    public void mode(WatchMode m) {
        var was = mode;
        mode = m;
        if (was == WatchMode.PAUSED && m.polls()) pollNow();
    }

    /** The usages of the last successful poll, in poll order (the active account first). */
    public Map<String, AccountUsage> lastUsages() { return lastUsages; }

    public String lastActiveId() { return lastActiveId; }

    /** One poll cycle without scheduling or switching — for {@code --once}. */
    public Decision runOnce() throws Exception {
        var activeId = swap.detectActiveId();
        var usages = collect(activeId);
        var decision = planner.decide(activeId, softLimit(activeId), Targets.of(usages, activeId, swap::isServable));
        if (decision.kind() == Decision.Kind.NONE && cfg.rebalance()) {
            var target = rebalancer.bestTarget(activeId, Targets.of(usages, activeId, swap::isServable), limitPercents());
            if (target.isPresent())
                decision = Decision.prompt(target.get(), "rebalance: " + activeId + " is at a higher normalized pace than " + target.get());
        }
        listener.onPoll(activeId, usages, decision);
        return decision;
    }

    private void tick() {
        if (nextTick != null) nextTick.cancel(false);
        if (!mode.polls()) { reschedule(); return; }
        String activeId = null;
        try {
            activeId = swap.detectActiveId();
            lastActiveId = activeId;
            var before = hold.lastSeen;
            if (hold.observe(activeId, System.currentTimeMillis()) && mode == WatchMode.LOCKED) listener.onLeftLocked(before, activeId);
            var usages = collect(activeId);
            lastUsages = usages;
            var decision = planner.decide(activeId, softLimit(activeId), Targets.of(usages, activeId, swap::isServable));
            errorStreak = 0;
            listener.onPoll(activeId, usages, decision);
            act(activeId, usages, decision);
        } catch (Exception e) {
            errorStreak++;
            var next = backoff();
            log.warn("watch poll failed ({}x): {} — retrying in {}s", errorStreak, e.toString(), next);
            // Identity (who's active) is cheap and always known even when usage fetching fails —
            // don't let a rate-limited quota check freeze the tray on a stale, possibly just-switched-away-from account.
            var known = new LinkedHashMap<>(lastUsages);
            known.remove(activeId);
            listener.onPoll(activeId, known, Decision.none("usage fetch failed"));
            listener.onError(e, next);
        } finally {
            reschedule();
        }
    }

    /** Usage is sourced via {@link UsageService#accountUsagePeriodic}, which never throws (it
     *  absorbs and backs off on failure internally) — this can still throw from identity/store
     *  lookups (e.g. {@code swap.eligible()}), which is what actually drives this loop's backoff. */
    private Map<String, AccountUsage> collect(String activeId) throws Exception {
        var usages = new LinkedHashMap<String, AccountUsage>();
        if (activeId != null) usages.put(activeId, usage.accountUsagePeriodic(activeId, true));
        for (var c : swap.eligible()) {
            var id = c.id();
            if (id.equals(activeId) || !swap.store().isRegistered(id)) continue;
            usages.put(id, usage.accountUsagePeriodic(id, false));
        }
        return usages;
    }

    private void act(String activeId, Map<String, AccountUsage> usages, Decision d) {
        switch (d.kind()) {
            case AUTO_SWITCH -> { if (cfg.autoSwitch() && mode.mayAutoSwitch()) doSwitch(d.targetId(), true); else maybePrompt(activeId, d.targetId(), usages); }
            case PROMPT -> maybePrompt(activeId, d.targetId(), usages);
            case NONE -> { if (cfg.rebalance() && mode.mayRebalance()) maybeRebalance(activeId, usages); }
        }
    }

    /** Lower-priority than the reactive planner: only runs when nothing urgent is happening. */
    private void maybeRebalance(String activeId, Map<String, AccountUsage> usages) {
        if (!hold.allowsRebalance(System.currentTimeMillis())) {
            log.debug("rebalance held for {} more minutes: the user chose {}", hold.minutesLeft(System.currentTimeMillis()), activeId);
            return;
        }
        rebalancer.bestTarget(activeId, Targets.of(usages, activeId, swap::isServable), limitPercents())
                .ifPresent(target -> doSwitch(target, true));
    }

    private Map<String, Double> limitPercents() {
        return swap.eligible().stream()
                .collect(Collectors.toMap(CCAPICredential::id, CCAPICredential::claudeCodeLimitPercent));
    }

    private void maybePrompt(String activeId, String targetId, Map<String, AccountUsage> usages) {
        var now = System.currentTimeMillis();
        if (Objects.equals(activeId, lastPromptId) && now - lastPromptAt < cfg.repromptGapSec() * 1000L) return;
        lastPromptId = activeId; lastPromptAt = now;
        listener.onPrompt(activeId, targetId, usages.get(activeId), usages.get(targetId));
    }

    private void doSwitch(String id, boolean automatic) {
        try {
            var r = swap.switchTo(id);
            if (automatic) hold.automatic(id); else hold.manual(id, System.currentTimeMillis());
            lastPromptId = null;
            listener.onSwitched(r, automatic);
        } catch (Exception e) {
            log.warn("switch to {} failed: {}", id, e.toString());
            listener.onError(e, 0);
        }
    }

    private double softLimit(String activeId) {
        if (cfg.softLimitOverride() > 0) return cfg.softLimitOverride();
        if (activeId == null) return 95.0;
        return swap.eligible().stream().filter(c -> c.id().equals(activeId))
                .findFirst().map(CCAPICredential::claudeCodeLimitPercent).orElse(95.0);
    }

    private int backoff() {
        var factor = 1L << Math.min(errorStreak, 16);
        return (int) Math.min(cfg.maxBackoffSec(), (long) cfg.baseIntervalSec() * factor);
    }

    private void reschedule() {
        if (exec.isShutdown()) return;
        var delay = errorStreak == 0 ? cfg.baseIntervalSec() : backoff();
        nextTick = exec.schedule(this::tick, delay, TimeUnit.SECONDS);
    }
}
