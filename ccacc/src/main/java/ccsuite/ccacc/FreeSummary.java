package ccsuite.ccacc;

import module java.base;

/** The free accounts in one line (the tray tooltip and the widget header), and the colour a reading deserves. */
final class FreeSummary {

    /** "Free overall: 5h 23% - week 4% - 9 of 12 with room"; the tray tooltip. */
    static String line(List<FreeGauges.Row> rows, double fiveLimit, double weekLimit, Instant now) {
        return rows.isEmpty() ? "Free accounts: not read yet" : FreeOverall.of(rows, fiveLimit, weekLimit, now).text();
    }

    static String avg(List<Double> fractions) {
        var known = fractions.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).toArray();
        return known.length == 0 ? "-" : Math.round(Arrays.stream(known).average().orElse(0) * 100) + "%";
    }

    /** The colour of everything that is about the free accounts (header bands, tray menu, card), teal, so it is never mistaken for the paid accounts' orange. */
    static final String COLOR = "#2a8c9c";

    /** Teal below 70%, amber to 90%, red above; grey when there is no reading. */
    static String colour(Double fraction) {
        return fraction == null ? "#9a9a9a" : fraction >= 0.9 ? "#b3261e" : fraction >= 0.7 ? "#e0a100" : "#2aa7b8";
    }

    private FreeSummary() {}
}
