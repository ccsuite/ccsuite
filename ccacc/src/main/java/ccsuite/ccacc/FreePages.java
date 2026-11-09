package ccsuite.ccacc;

import module java.base;
import ccsuite.usagetracker.model.AccountUsage;

/** The free accounts as the pages every view draws (the card, the usage window, the Free Accounts tab), made from the gauge table already held; and the read-again that all of them offer. */
final class FreePages {

    static List<AccountUsage> pages(FreeGauges gauges, Instant now) {
        return FreeCard.accounts(FreePane.entries(gauges, now, ZoneId.systemDefault()), now);
    }

    /** ALL, the first page of the free card and the first row of the window; null with fewer than two accounts. */
    static AccountUsage all(FreeGauges gauges, LaneSettings limits, List<AccountUsage> pages, Instant now) {
        return pages.size() < 2 ? null : FreeCard.overall(gauges.snapshot(), limits.freeFiveLimit(), limits.freeWeekLimit(), now);
    }

    /** Probes that page's account again now (every free account for ALL); the failure in the server's own words, or null. Off the FX thread. */
    static String reread(FreeGauges gauges, AccountUsage page) {
        var free = FreeGauges.free(gauges.accounts.get());
        if (!"ALL".equals(page.credential().id())) {
            var c = free.stream().filter(x -> x.id().equals(page.credential().id())).findFirst();
            return c.isEmpty() ? "No such free account any more." : gauges.refresh(c.get()).problem();
        }
        gauges.refreshAll();
        var failed = gauges.snapshot().stream().filter(r -> r.problem() != null).map(r -> r.id() + ": " + r.problem()).toList();
        return failed.isEmpty() ? null : String.join("\n", failed);
    }

    private FreePages() {}
}
