package ccsuite.ccacc;

import module java.base;
import ccsuite.base.model.CCAPICredential;

/**
 * Which free accounts need a ping now (PRP 79): a pure function of the accounts, their last readings and the time. A ping opens the five-hour window of an
 * account nobody has touched, so that the window is already running (and its end already near) when the account is needed. An account whose reading is young
 * and whose window is still running needs none; the reading may come from a probe, a served call or the ledger.
 */
final class FreePing {

    /** An account to ping and why, in words for the log. */
    record Due(CCAPICredential account, String why) { }

    /** The accounts that may be pinged: free (tier 0, a session key), not monitor-only (the store flag active plays no part), and among the lane's named accounts when it names some. */
    static List<CCAPICredential> eligible(List<CCAPICredential> all, Collection<String> named) {
        return FreeGauges.free(all).stream().filter(c -> !c.watchOnly() && (named.isEmpty() || named.contains(c.id()))).toList();
    }

    static List<Due> due(List<CCAPICredential> accounts, Map<String, FreeGauges.Row> rows, Instant now, Duration fresh) {
        var out = new ArrayList<Due>();
        for (var c : accounts) why(rows.get(c.id()), now, fresh).ifPresent(w -> out.add(new Due(c, w)));
        return out;
    }

    /** How long an account whose restore time is not known waits between probes: it is probably not started, a ping starts it, and the reading then gives the time. */
    static final Duration UNKNOWN_EVERY = Duration.ofMinutes(60);

    static Optional<String> why(FreeGauges.Row r, Instant now, Duration fresh) {
        if (r == null) return Optional.of("never read");
        var reset = r.resetsAt();
        var spent = exhausted(r);
        if (spent && reset != null && reset > now.getEpochSecond()) return Optional.empty();
        if (!r.ok()) return Optional.of("the last ping failed: " + r.problem());   // an error is tried again at the next round
        if (r.five() == null || spent && reset == null) {   // restore time not known: now when never probed, then hourly
            var age = Duration.between(r.read(), now);
            return age.compareTo(UNKNOWN_EVERY) >= 0 ? Optional.of("restore time not known, last probe " + age.toMinutes() + " min ago") : Optional.empty();
        }
        if (spent && reset != null) return Optional.of("the used-up window has reset");
        if (reset != null && reset <= now.getEpochSecond()) return Optional.of("the window has reset");
        var age = Duration.between(r.read(), now);
        return age.compareTo(fresh) >= 0 ? Optional.of("the reading is " + age.toMinutes() + " min old") : Optional.empty();
    }

    /** The five-hour window is used up: at or over 100%, or the server said so (a served call leaves the status at within_limit, so the number counts as much as the word). */
    static boolean exhausted(FreeGauges.Row r) {
        return r != null && ("exceeded_limit".equals(r.status()) || r.five() != null && r.five() >= 1.0);
    }

    private FreePing() {}
}
