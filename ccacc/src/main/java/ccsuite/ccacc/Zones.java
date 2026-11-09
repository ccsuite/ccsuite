package ccsuite.ccacc;

import module java.base;

/**
 * PRP 86: the ONE policy that says when a PRP cycle is warned and closed, in input tokens. The hook, the status line and the messages the server sends all read these numbers
 * (through {@code ccacc lanes zones}), so there is a single set. Free lane: the hard limit is the safe start ({@link LaneSettings#safeStartTokens}) scaled by the room the serving
 * account has left; close and warn follow it down, but close never goes below the start cost plus a working margin (a close at 100,000 would leave no work), and never above hard.
 * Paid lane: fixed numbers, independent of any room (the prompt cache carries a long conversation).
 *
 * @param warn  one line is added to the system prompt: finish the step, then close the cycle
 * @param close the order: update the PRP status now, export the logs, run the instructions, say the cycle is closed
 * @param hard  a conversation above this cannot safely be closed on the serving account
 * @param room  the points left in the serving account's window (0 to 100); 100 for the paid profile
 */
record Zones(long warn, long close, long hard, double room) {

    /** The zones of the free lane for an account with this much room left (points of its five-hour window, clamped to 0..100). */
    static Zones free(LaneSettings s, double room) {
        var r = Math.max(0, Math.min(100, room));
        var hard = Math.round(s.safeStartTokens() * r / 100.0);
        var floor = Math.round(s.startCostTokens() + s.workMarginTokens());
        var scaled = Math.round(s.freeCloseTokens() * r / 100.0);
        var close = Math.min(hard, Math.max(scaled, floor));
        var warn = Math.min(close, Math.round(close * s.freeWarnTokens() / s.freeCloseTokens()));
        return new Zones(warn, close, hard, r);
    }

    /** The zones of the paid lane: fixed. */
    static Zones paid(LaneSettings s) {
        return new Zones(Math.round(s.paidWarnTokens()), Math.round(s.paidCloseTokens()), Math.round(s.paidHardTokens()), 100);
    }

    /** The zone an input size of this many tokens is in: work, warn, close or hard. */
    String judge(long inputTokens) {
        if (inputTokens >= hard) return "hard";
        if (inputTokens >= close) return "close";
        if (inputTokens >= warn) return "warn";
        return "work";
    }

    /** {@code key=value} lines under a prefix, for the hook and the status line: read-only. */
    List<String> lines(String prefix) {
        var roomText = room == Math.rint(room) ? Long.toString(Math.round(room)) : String.format(Locale.ROOT, "%.1f", room);
        return List.of(prefix + ".warn=" + warn, prefix + ".close=" + close, prefix + ".hard=" + hard, prefix + ".room=" + roomText);
    }
}
