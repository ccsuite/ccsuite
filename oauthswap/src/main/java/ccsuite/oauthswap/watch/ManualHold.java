package ccsuite.oauthswap.watch;

/**
 * Respects a switch the user made: after it, the watcher does not rebalance away for a while. A switch counts as the
 * user's when it was asked for (the tray, the window, the CLI through ccacc) or when Claude Code turns up on another
 * account than the watcher left it on (a /login, another tool). Switching away from a depleted account is not held:
 * that protects the account, the user's choice included.
 */
final class ManualHold {

    static final long HOLD_MS = 30 * 60 * 1000;

    String lastSeen;
    long until;

    /** Each poll: an account change the watcher did not make is the user's (or another program's). Returns whether there was one. */
    boolean observe(String activeId, long now) {
        var outside = lastSeen != null && activeId != null && !activeId.equals(lastSeen);
        if (outside) until = now + HOLD_MS;
        lastSeen = activeId;
        return outside;
    }

    /** A switch the user asked for. */
    void manual(String toId, long now) {
        until = now + HOLD_MS;
        lastSeen = toId;
    }

    /** A switch the watcher made on its own: not a reason to hold, and not a change to mistake for the user's. */
    void automatic(String toId) { lastSeen = toId; }

    boolean allowsRebalance(long now) { return now >= until; }

    long minutesLeft(long now) { return Math.max(0, (until - now + 59_999) / 60_000); }
}
