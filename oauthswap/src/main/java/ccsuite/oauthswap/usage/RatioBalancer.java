package ccsuite.oauthswap.usage;

import ccsuite.oauthclient.*;

import java.util.*;

/**
 * Normalizes weekly burn-rate against each account's own configured pace limit, so accounts
 * with different {@code claudecodelimit} targets can be compared on one scale. For account
 * {@code i}, {@code n_i = weeklyUsageRatio_i / (claudecodelimit_i/100)}: {@code n_i == 1} means
 * exactly at that account's own configured pace tolerance; picking the lowest {@code n_i} each
 * time naturally equalizes pace across accounts (no separate distribution formula needed).
 */
public final class RatioBalancer {

    private RatioBalancer() {}

    /** {@code sevenDayAverage()/limitPercent}, or {@code null} if either input is unknown/invalid. */
    public static Double normalizedRatio(AccountUsage usage, Double claudecodeLimitPercent) {
        if (usage == null || claudecodeLimitPercent == null || claudecodeLimitPercent <= 0) return null;
        var weekly = usage.sevenDayAverage();
        return weekly == null ? null : weekly / claudecodeLimitPercent;
    }

    /**
     * Eligible accounts ranked ascending by {@link #normalizedRatio}: best (most headroom
     * relative to its own limit) first. Excludes accounts with unknown usage/limit data or
     * whose overall {@link AccountUsage#headroom()} is below {@code minHeadroom}.
     */
    public static List<String> rank(Map<String, AccountUsage> usages, Map<String, Double> limitPercents, double minHeadroom) {
        var ranked = new ArrayList<Map.Entry<String, Double>>();
        for (var e : usages.entrySet()) {
            var usage = e.getValue();
            if (usage == null) continue;
            var headroom = usage.headroom();
            if (headroom == null || headroom < minHeadroom) continue;
            var ratio = normalizedRatio(usage, limitPercents.get(e.getKey()));
            if (ratio == null) continue;
            ranked.add(Map.entry(e.getKey(), ratio));
        }
        ranked.sort(Map.Entry.comparingByValue());
        return ranked.stream().map(Map.Entry::getKey).toList();
    }
}
