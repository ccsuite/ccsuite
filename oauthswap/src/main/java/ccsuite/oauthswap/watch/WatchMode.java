package ccsuite.oauthswap.watch;

/**
 * What the watcher may do on its own. {@link #AUTO}: switch on depletion and rebalance, as
 * configured. {@link #LOCKED}: stay on the current account (no automatic switch, no rebalance)
 * but keep polling and still raise the prompt near the limit, so an account is never run down
 * unnoticed; only an explicit switch from the user moves it. {@link #PAUSED}: no polling at all,
 * so no usage probe is spent while the user works on one account by hand.
 */
public enum WatchMode {
    AUTO, LOCKED, PAUSED;

    public boolean polls() { return this != PAUSED; }

    public boolean mayAutoSwitch() { return this == AUTO; }

    public boolean mayRebalance() { return this == AUTO; }
}
