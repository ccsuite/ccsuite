package ccsuite.ccacc.trim;

import module java.base;

/**
 * The resident app's side: every {@code checkSeconds} look at the claude.exe processes, remember who is busy (always, so that {@code ccacc trim status} can show idle times before trimming is
 * switched on) and trim the idle ones (only while {@code enabled}). Settings are read again at every look, so {@code ccacc trim on|off|set} act within one interval without a message to the app.
 */
public final class TrimLoop {

    /** Starts the loop; {@code laneBusy} says whether a call is in flight on a lane. Does nothing off Windows. */
    public static void start(BooleanSupplier laneBusy) {
        if (!Win.available()) return;
        var trimmer = new Trimmer(new WinProbe(), Clock.systemDefaultZone(), laneBusy, Tabs::fetch);
        var state = TrimState.load(TrimState.file());
        Thread.ofPlatform().name("ccacc-trim").daemon().start(() -> {
            while (true) {
                var cfg = TrimSettingsStore.load();
                try {
                    pass(trimmer, cfg, state);
                    Thread.sleep(Math.max(5, cfg.checkSeconds()) * 1000L);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable e) {
                    System.err.println("ccacc trim: " + e);
                }
            }
        });
    }

    static void pass(Trimmer trimmer, TrimSettings cfg, TrimState state) {
        var done = trimmer.tick(cfg, state, p -> true);
        done.forEach(o -> TrimLog.append(TrimLog.file(), o, Instant.now()));
        state.save(TrimState.file());
    }

    private TrimLoop() {}
}
