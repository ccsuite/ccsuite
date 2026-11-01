package ccsuite.oauthswap.watch;

import ccsuite.oauthswap.SwitchResult;
import ccsuite.oauthclient.AccountUsage;

import java.util.Map;

/** Observer hooks the watcher invokes off the UI thread; implementations marshal as needed. */
public interface WatchListener {

    void onPoll(String activeId, Map<String, AccountUsage> usages, Decision decision);

    void onPrompt(String activeId, String targetId, AccountUsage active, AccountUsage target);

    void onSwitched(SwitchResult result, boolean automatic);

    /** A switch with the watcher's reason for it ({@code null} for one the user asked for). */
    default void onSwitched(SwitchResult result, boolean automatic, String reason) { onSwitched(result, automatic); }

    void onError(Throwable error, int nextIntervalSec);

    /** In Locked mode, Claude Code turned up on another account than the locked one, and the watcher did not switch it (a running Claude Code session writing its own login back, or another tool). */
    default void onLeftLocked(String lockedId, String nowId) { }
}
