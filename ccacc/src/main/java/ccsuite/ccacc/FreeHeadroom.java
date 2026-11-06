package ccsuite.ccacc;

import module java.base;

/** Whether the free pool has room, from the gauge rows: pure, so the BALANCED decision can be tested with fake gauges. */
final class FreeHeadroom {

    /** True when some account in {@code ids} has room. An account not read yet, or read without numbers, counts as having room (the lane copes with a refusal). */
    static boolean any(Collection<FreeGauges.Row> rows, Collection<String> ids, double fiveLimit, double weekLimit, Instant now) {
        var byId = new HashMap<String, FreeGauges.Row>();
        rows.forEach(r -> byId.put(r.id(), r));
        return ids.stream().anyMatch(id -> open(byId.get(id), fiveLimit, weekLimit, now));
    }

    /** An account is open unless its five-hour window is full and has not reset yet, or its week is full. */
    static boolean open(FreeGauges.Row r, double fiveLimit, double weekLimit, Instant now) {
        if (r == null || (r.five() == null && r.seven() == null)) return true;
        var reset = r.resetsAt() != null && r.resetsAt() <= now.getEpochSecond();
        var fiveFull = r.five() != null && r.five() >= fiveLimit && !reset;
        var weekFull = r.seven() != null && r.seven() >= weekLimit;
        return !fiveFull && !weekFull;
    }

    private FreeHeadroom() {}
}
