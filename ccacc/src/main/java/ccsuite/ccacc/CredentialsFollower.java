package ccsuite.ccacc;

import ccsuite.base.config.CredentialsManager;
import ccsuite.base.model.CCAPIsCredentials;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the app's accounts in step with the file when someone else changes it (the CLI, another tool): every few
 * seconds it looks at the file's time, and only when that moved does it read the file and take it in. Cheap when
 * nothing happens: one file-time read.
 */
final class CredentialsFollower {

    static final long EVERY_MS = 3000;

    final CredentialsManager manager;
    final CCAPIsCredentials shared;
    final Runnable changed;
    long seen;
    ScheduledExecutorService timer;

    CredentialsFollower(CredentialsManager manager, CCAPIsCredentials shared, Runnable changed) {
        this.manager = manager;
        this.shared = shared;
        this.changed = changed;
        this.seen = manager.lastModified();
    }

    void start() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("ccacc-accounts-follower").unstarted(r));
        timer.scheduleWithFixedDelay(this::check, EVERY_MS, EVERY_MS, TimeUnit.MILLISECONDS);
    }

    void stop() { if (timer != null) timer.shutdownNow(); }

    /** Takes the file in when it changed since last seen; returns whether it did. */
    boolean check() {
        var now = manager.lastModified();
        if (now == seen) return false;
        try {
            Accounts.adopt(shared, manager.load());
            seen = now;
            changed.run();
            return true;
        } catch (Exception e) {
            System.err.println("ccacc: could not read the changed accounts file (will try again): " + e.getMessage());
            return false;
        }
    }
}
