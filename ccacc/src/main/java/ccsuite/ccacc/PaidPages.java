package ccsuite.ccacc;

import module java.base;
import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.Usage;
import ccsuite.base.model.UsageData;
import ccsuite.oauthclient.AccountUsage;
import ccsuite.usagetracker.model.LastReadings;
import ccsuite.usagetracker.taskbar.AllPage;

/** The watcher's last poll of the paid accounts as the pages the usage views draw (the type of the card and the usage window), and their ALL page. */
final class PaidPages {

    static List<ccsuite.usagetracker.model.AccountUsage> of(Map<String, AccountUsage> usages, List<CCAPICredential> credentials) {
        return of(usages, credentials, Map.of(), Instant.now());
    }

    /**
     * The same, and an account the watcher has no reading for (used up, or the call failed) is not left out: its last reading from the log is its page, dated when it
     * was taken, so the views show when it comes back and how old that reading is.
     */
    static List<ccsuite.usagetracker.model.AccountUsage> of(Map<String, AccountUsage> usages, List<CCAPICredential> credentials, Map<String, LastReadings.Reading> logged, Instant now) {
        var pages = polled(usages, credentials);
        for (var c : credentials) {
            if (!ccsuite.base.model.CCAPIsCredentials.paid(c) || !c.trackUsage() || pages.stream().anyMatch(p -> p.credential().id().equals(c.id()))) continue;
            var line = logged.get(c.id());
            if (line != null) pages.add(LastReadings.page(c, line, now));
        }
        return pages;
    }

    /** One page per polled account, in poll order; the account's own credential when it is known, so its name and e-mail show. */
    static List<ccsuite.usagetracker.model.AccountUsage> polled(Map<String, AccountUsage> usages, List<CCAPICredential> credentials) {
        var pages = new ArrayList<ccsuite.usagetracker.model.AccountUsage>();
        usages.forEach((id, u) -> {
            if (u == null) return;
            var credential = credentials.stream().filter(c -> id.equals(c.id())).findFirst().orElseGet(() -> new CCAPICredential().id(id).tier(1).active(true));
            if (!ccsuite.base.model.CCAPIsCredentials.paid(credential)) return;   // a free account is shown in the free views only
            var data = new UsageData();
            if (u.fiveHourPct() != null) data.fiveHour(new Usage().utilization(u.fiveHourPct()).resetsAt(at(u.fiveHourResetsAt())));
            if (u.sevenDayPct() != null) data.sevenDay(new Usage().utilization(u.sevenDayPct()).resetsAt(at(u.sevenDayResetsAt())));
            pages.add(new ccsuite.usagetracker.model.AccountUsage(credential, data));
        });
        return pages;
    }

    /** All accounts as one: the averages of the card's ALL page; null with fewer than two accounts or nothing to average. */
    static ccsuite.usagetracker.model.AccountUsage all(List<ccsuite.usagetracker.model.AccountUsage> pages) {
        return AllPage.page(AllPage.total(pages), pages.size());
    }

    static OffsetDateTime at(String iso) {
        try { return iso == null || iso.isBlank() ? null : OffsetDateTime.parse(iso); }
        catch (RuntimeException e) { return null; }
    }

    private PaidPages() {}
}
