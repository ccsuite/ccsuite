package ccsuite.ccacc;

import module java.base;
import ccsuite.base.model.CCAPICredential;

/**
 * The ping round of the free accounts (PRP 79). Every {@code freePingEveryMinutes} it asks {@link FreePing} which accounts are due and sends each one small message
 * ({@link FreeGauges#refresh}, the same one-word Haiku probe), a few seconds apart, one line per ping in the ledger ({@link FreeLedger.Ping}). Nothing is sent when no account is due, and
 * nothing at all when there is no free account to ping. A failed ping leaves its account unread, so the next round tries it again; there is no retry loop. At start the ledger's
 * recent readings are taken in, so a restart does not ping accounts a served call has just read.
 */
final class FreePinger {

    final FreeGauges gauges;
    final Supplier<List<CCAPICredential>> accounts;
    final Supplier<LaneSettings> settings;
    final FreeLedger ledger;
    final Consumer<Duration> pause;
    ScheduledExecutorService timer;
    volatile boolean stopped;

    FreePinger(FreeGauges gauges, Supplier<List<CCAPICredential>> accounts, Supplier<LaneSettings> settings, FreeLedger ledger) {
        this(gauges, accounts, settings, ledger, d -> { try { Thread.sleep(d); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
    }

    FreePinger(FreeGauges gauges, Supplier<List<CCAPICredential>> accounts, Supplier<LaneSettings> settings, FreeLedger ledger, Consumer<Duration> pause) {
        this.gauges = gauges; this.accounts = accounts; this.settings = settings; this.ledger = ledger; this.pause = pause;
    }

    /** The readings the ledger holds from the last six hours (a window lasts five), as known readings: an old one is stale and gets pinged, but an exhausted one still says when its window resets. */
    void seed(Instant now) {
        ledger.latestReadings(now.minus(Duration.ofHours(6))).forEach(r -> gauges.seed(r.account(), r.five(), r.seven(), r.reset5(), r.t()));
    }

    /** One round: the pings it sent, in order. */
    List<FreeLedger.Ping> round(Instant now) {
        var s = settings.get();
        var eligible = FreePing.eligible(accounts.get(), s.freeAccounts());
        var fresh = Duration.ofMinutes(s.freePingFreshMinutes());
        var due = FreePing.due(eligible, gauges.table(), now, fresh);
        var sent = new ArrayList<FreeLedger.Ping>();
        for (var d : due) {
            if (stopped) break;
            if (!sent.isEmpty()) pause.accept(Duration.ofSeconds(s.freePingSpreadSeconds()));
            if (FreePing.why(gauges.rows.get(d.account().id()), now, fresh).isEmpty()) continue;   // a reading arrived while the round was running: look again just before the ping
            var row = gauges.refresh(d.account(), "ping");
            var ping = new FreeLedger.Ping(row.read(), row.id(), d.why(), row.ok() ? row.five() : null, row.ok() ? row.seven() : null, row.ok() ? row.resetsAt() : null, row.ok() ? row.status() : null, row.problem());
            ledger.append(ping);
            sent.add(ping);
        }
        return sent;
    }

    synchronized void start() {
        stop();
        stopped = false;
        timer = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "free-pinger"); t.setDaemon(true); return t; });
        timer.execute(() -> { try { seed(Instant.now()); } catch (RuntimeException e) { System.err.println("ccacc: free pinger seed: " + e); } tick(); });
    }

    private void tick() {
        try { round(Instant.now()); } catch (RuntimeException e) { System.err.println("ccacc: free ping round: " + e); }
        synchronized (this) {
            if (timer != null && !stopped) timer.schedule(this::tick, Math.max(1, settings.get().freePingEveryMinutes()), TimeUnit.MINUTES);
        }
    }

    synchronized void stop() {
        stopped = true;
        if (timer != null) timer.shutdownNow();
        timer = null;
    }
}
