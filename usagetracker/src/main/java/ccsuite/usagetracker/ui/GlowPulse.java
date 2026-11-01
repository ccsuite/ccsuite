package ccsuite.usagetracker.ui;

import ccsuite.base.usage.Pace;
import ccsuite.usagetracker.model.AccountUsage;
import javafx.scene.paint.Color;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * The glow of the account Claude Code is on: a calm golden beat, yellow to gold and back. It turns to a yellow-to-red
 * beat only when that account is being spent too fast (see {@link #danger}). Pure functions of the clock, so the card
 * and the widget row beat together. Whoever draws it redraws a few times a second, only while visible (an endless
 * JavaFX animation would keep the frame clock running).
 */
public final class GlowPulse {

    public static final long PERIOD_MS = 1400, TICK_MS = 140;
    static final Color YELLOW = Color.web("#FFE600"), GOLD = Color.web("#FFB000"), RED = Color.web("#FF2200");

    /** 0 at the light end, 1 at the deep end, a smooth wave of the clock. */
    public static double wave(long nowMs) {
        return 0.5 - 0.5 * Math.cos(2 * Math.PI * (nowMs % PERIOD_MS) / PERIOD_MS);
    }

    public static Color fire(boolean danger) { return fire(System.currentTimeMillis(), danger); }

    public static Color fire(long nowMs, boolean danger) { return YELLOW.interpolate(danger ? RED : GOLD, wave(nowMs)); }

    /** Blur radius of the glow, widest at the deep end. */
    public static double radius() { return radius(System.currentTimeMillis()); }

    public static double radius(long nowMs) { return 4 + 9 * wave(nowMs); }

    /**
     * Spending too fast: more than half of the 5-hour quota used while using it faster than the window passes,
     * or the weekly quota used faster than the week passes once more than half the week has gone. In percent;
     * a time of 0 has no rate.
     */
    public static boolean danger(double fiveUsage, double fiveTime, double weekUsage, double weekTime) {
        return Pace.fiveHourTooFast(fiveUsage, fiveTime) || Pace.weekTooFast(weekUsage, weekTime);
    }

    public static boolean danger(AccountUsage a) {
        var d = a == null ? null : a.usageData();
        if (d == null) return false;
        var five = d.fiveHour();
        var week = d.sevenDay();
        return danger(five == null ? 0 : five.utilization(), five == null ? 0 : elapsed(five.resetsAt(), 5 * 3600L),
                week == null ? 0 : week.utilization(), week == null ? 0 : elapsed(week.resetsAt(), 7 * 24 * 3600L));
    }

    /** The share of a window that has passed, from when it resets; 0 when that is not known. */
    static double elapsed(OffsetDateTime resets, long windowSecs) {
        if (resets == null) return 0;
        var gone = (windowSecs - Duration.between(OffsetDateTime.now(), resets).getSeconds()) * 100.0 / windowSecs;
        return Math.max(0, Math.min(100, gone));
    }

    private GlowPulse() {}
}
