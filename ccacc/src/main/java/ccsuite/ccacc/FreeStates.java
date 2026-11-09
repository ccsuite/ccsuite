package ccsuite.ccacc;

import module java.base;
import ccsuite.base.model.CCAPICredential;

/**
 * The explicit state of every free account, the same set and the same order in every view (ccacc lanes, ccacc free, the tray, the widget): available first (most room first),
 * then exhausted by restore time (soonest first), then restore time not known (oldest probe first). The state comes from the restore time against the current time, every time:
 * the store flag {@code active} plays no part, and there is no "inactive" state. Pure: a function of the accounts, the gauge table and the time.
 */
final class FreeStates {

    enum Kind { AVAILABLE, EXHAUSTED, RESTORE_UNKNOWN }

    /** {@code row} is the account's last reading (null when never read); {@code pending}: the lane would serve it but only after its next restart. */
    record Entry(String id, Kind kind, String text, FreeGauges.Row row, boolean pending) {

        /** What the views print after the id: the state, and the restart note when there is one. */
        String describe() { return pending ? text + "; added, served after the next restart" : text; }

        double key() {
            return switch (kind) {
                case AVAILABLE -> -(row == null || row.five() == null || FreePing.exhausted(row) ? 0 : 1 - row.five());
                case EXHAUSTED -> row == null || row.resetsAt() == null ? Double.MAX_VALUE : row.resetsAt();
                case RESTORE_UNKNOWN -> row == null || row.read() == null ? Double.NEGATIVE_INFINITY : row.read().getEpochSecond();   // oldest probe first, never probed before all
            };
        }
    }

    static List<Entry> entries(List<CCAPICredential> all, Map<String, FreeGauges.Row> table, Collection<String> pending, Instant now, ZoneId zone) {
        var out = new ArrayList<Entry>();
        for (var c : FreeGauges.free(all)) out.add(entry(c, table.get(c.id()), pending.contains(c.id()), now, zone));
        out.sort(Comparator.comparingInt((Entry e) -> e.kind().ordinal()).thenComparingDouble(Entry::key).thenComparing(Entry::id));
        return out;
    }

    /**
     * The state of one account from its last reading and the time. A restore time in the future: exhausted until then. A restore time that has passed: available now. No restore time
     * (never read, or a refusal that gave none; usually an account that was never started, which a ping starts): not dead, probed hourly until a reading gives the time.
     */
    static Entry entry(CCAPICredential c, FreeGauges.Row row, boolean pending, Instant now, ZoneId zone) {
        var reset = row == null ? null : row.resetsAt();
        var spent = FreePing.exhausted(row);
        if (row == null || row.five() == null || spent && reset == null) {
            var probe = row == null || row.read() == null || Instant.EPOCH.equals(row.read()) ? "not probed yet" : "last probe " + time(row.read().getEpochSecond(), zone);
            return new Entry(c.id(), Kind.RESTORE_UNKNOWN, "restore time not known yet, probing hourly, " + probe, row, pending);
        }
        if (reset != null && reset > now.getEpochSecond()) {
            if (spent) return new Entry(c.id(), Kind.EXHAUSTED, "exhausted, " + resets(reset, now, zone), row, pending);
            var left = Math.max(0, Math.round((1 - row.five()) * 100));
            return new Entry(c.id(), Kind.AVAILABLE, left + " points left, " + resets(reset, now, zone), row, pending);
        }
        if (reset != null) return new Entry(c.id(), Kind.AVAILABLE, "available, window reset at " + time(reset, zone), row, pending);
        var left = Math.max(0, Math.round((1 - row.five()) * 100));
        return new Entry(c.id(), Kind.AVAILABLE, left + " points left", row, pending);
    }

    static String resets(long epochSeconds, Instant now, ZoneId zone) {
        return "resets at " + time(epochSeconds, zone) + " (in " + FreeGauges.untilReset(epochSeconds, now) + ")";
    }

    static String time(long epochSeconds, ZoneId zone) {
        return DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochSecond(epochSeconds));
    }

    /** One line of the command and the window: id, the state, then the bars when the account has been read, and how long ago. */
    static String line(Entry e, Instant now) {
        var r = e.row();
        var bars = r == null || r.five() == null ? "" : String.format("   5h %s %s  week %s  read %s", FreeGauges.bar(r.five()), FreeGauges.percent(r.five()), FreeGauges.percent(r.seven()), FreeGauges.ago(r.read(), now));
        return String.format("%-5s %s%s", e.id(), e.describe(), bars);
    }

    static FreeGauges.Row blank(String id) {
        return new FreeGauges.Row(id, "", null, null, null, null, Instant.EPOCH, null, null, 0);
    }

    private FreeStates() {}
}
