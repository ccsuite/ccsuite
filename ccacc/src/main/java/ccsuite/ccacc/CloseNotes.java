package ccsuite.ccacc;

import module java.base;

/**
 * PRP 86 R2: the line the free lane adds to a request when a session's conversation has reached its warn or close point ({@link Zones}). Pure decision plus the decision log:
 * the caller says the session, the serving account, the estimated input tokens and the account's room, and gets the line to add (or null). Once per cycle: a warn line once, the
 * close order once; nothing while the order is open (the close turns themselves are never told again), nothing for a request without a session id. A cycle ends when the
 * input falls back under the warn point (a new conversation or a compaction) or when {@link #markClosed} is called. Every note given is appended to the decision log (PRP 86 3b),
 * to be checked later against the real count of that request; a log that cannot be written never stops the call (fail open).
 */
final class CloseNotes {

    private enum State { WARNED, ORDERED, CLOSED }

    private final LaneSettings settings;
    private final Path log;
    private final Map<String, State> states = new HashMap<>();

    CloseNotes(LaneSettings settings, Path log) { this.settings = settings; this.log = log; }

    /** The line to add to this request, or null (no gauge reading known). */
    synchronized String note(String session, String account, long inputTokens, double room) { return note(session, account, inputTokens, room, null, null); }

    /** The line to add to this request, or null. {@code five} and {@code seven} are the account's gauge as the lane has it at this call (fractions, null when unknown): written to the log with each decision. */
    synchronized String note(String session, String account, long inputTokens, double room, Double five, Double seven) {
        if (session == null || session.isBlank()) return null;
        var zones = Zones.free(settings, room);
        var zone = zones.judge(inputTokens);
        learnClosed(session);
        var state = states.get(session);
        if (zone.equals("work")) { states.remove(session); return null; }   // a new cycle: back under the warn point
        if (zone.equals("warn")) {
            if (state != null) return null;
            states.put(session, State.WARNED);
            record("warn", session, account, inputTokens, zones, five, seven);
            return warnLine(inputTokens, zones);
        }
        if (state == State.ORDERED || state == State.CLOSED) return null;
        states.put(session, State.ORDERED);
        record("close", session, account, inputTokens, zones, five, seven);
        return orderLine(inputTokens, zones);
    }

    /** True while the close order has been given and the cycle is not yet closed. */
    synchronized boolean orderOpen(String session) { learnClosed(session); return states.get(session) == State.ORDERED; }

    /** The Stop hook appends {@code closed session=X} to the decision log when both proj commands ran: a closed line after the latest close of this session ends the order. Fails open. */
    private void learnClosed(String session) {
        if (states.get(session) != State.ORDERED) return;
        try {
            if (!Files.isRegularFile(log)) return;
            var closeSeen = false;
            var closedAfterLatestClose = false;
            for (var line : Files.readAllLines(log, java.nio.charset.StandardCharsets.UTF_8)) {
                var parts = line.strip().split(" ");
                if (parts.length < 3 || !("session=" + session).equals(parts[parts.length - 1])) continue;
                if (parts[1].equals("close")) { closeSeen = true; closedAfterLatestClose = false; }   // a new order: older closed lines no longer count
                else if (parts[1].equals("closed") && closeSeen) closedAfterLatestClose = true;
            }
            if (closedAfterLatestClose) states.put(session, State.CLOSED);
        } catch (Exception e) {
            // fail open: the order stays as it is
        }
    }

    /** The session said the cycle is closed: no new order until its size falls back under the warn point. */
    synchronized void markClosed(String session) { if (states.containsKey(session)) states.put(session, State.CLOSED); }

    static String warnLine(long tokens, Zones z) {
        return "(fclaude: this conversation is at " + k(tokens) + " of the close point " + k(z.close()) + " tokens: finish the step in hand, then close the cycle.)";
    }

    static String orderLine(long tokens, Zones z) {
        return "(fclaude: this conversation is at " + k(tokens) + ", past the close point " + k(z.close()) + " tokens: close the cycle now. First update the PRP status in this conversation, "
                + "then run `proj prp logs` and `proj prp instructions` for real, then say the cycle is closed.)";
    }

    private static String k(long tokens) { return Math.round(tokens / 1000.0) + "K"; }

    private void record(String kind, String session, String account, long tokens, Zones z, Double five, Double seven) {
        try {
            var room = z.lines("x").get(3).substring(2);   // room=N, formatted as the zones command prints it
            var gauge = " five=" + (five == null ? "-" : five) + " seven=" + (seven == null ? "-" : seven);   // the session stays the last field: learnClosed reads it from the end
            var line = Instant.now() + " " + kind + " tokens=" + tokens + " warn=" + z.warn() + " close=" + z.close() + " hard=" + z.hard() + " " + room + gauge + " account=" + account + " session=" + session + "\n";
            if (log.getParent() != null) Files.createDirectories(log.getParent());
            Files.writeString(log, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            // fail open: the note is still given
        }
    }
}
