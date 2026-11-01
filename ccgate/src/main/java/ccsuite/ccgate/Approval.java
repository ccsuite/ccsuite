package ccsuite.ccgate;

import module java.base;

/** What the owner granted at the window: the model families, the daily points of each, and for how many days. */
public record Approval(List<String> models, Map<String, Double> daily, int days) {

    public static final List<String> TIERS = List.of("haiku", "sonnet", "opus");
    static final Map<String, Double> DAILY = Map.of("haiku", 100.0, "sonnet", 30.0, "opus", 15.0);

    /** The families up to and including the given tier: tier 0 = haiku, 1 = haiku and sonnet, 2 = all three. */
    public static Approval of(int tier, int days) {
        var models = TIERS.subList(0, tier + 1);
        var daily = new LinkedHashMap<String, Double>();
        models.forEach(m -> daily.put(m, DAILY.get(m)));
        return new Approval(List.copyOf(models), daily, days);
    }

    /** The smallest tier that allows the family the caller asked for (haiku when it asked for none). */
    public static int tierFor(String family) {
        var i = TIERS.indexOf(family);
        return Math.max(i, 0);
    }
}
