package ccsuite.ccacc;

import module java.base;
import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.Usage;
import ccsuite.base.model.UsageData;
import ccsuite.usagetracker.model.AccountUsage;
import ccsuite.usagetracker.taskbar.SlotInstaller;
import ccsuite.usagetracker.taskbar.TaskbarCard;

/**
 * The free accounts' card in the taskbar, drawn exactly like the usage card of the paid accounts and placed next to it (above it on a vertical taskbar, to its left
 * on a horizontal one). Its first page is ALL, the average of every free account; then one page per account (click or scroll). Fed from {@link FreeGauges}, so it
 * costs no probe of its own. Windows only (the slot is a Windows taskbar band).
 */
final class FreeCard {

    final FreeGauges gauges;
    final LaneSettings limits;
    TaskbarCard card;

    FreeCard(FreeGauges gauges, LaneSettings limits) { this.gauges = gauges; this.limits = limits; }

    /** Installs the slot (safe to repeat), then puts the card in it; the card says how many accounts have room in its footer. Call on the FX thread. */
    void start() {
        try { System.out.println("🧩 Taskbar slot registered: " + SlotInstaller.install()); }
        catch (Exception e) { System.err.println("⚠️  Taskbar slot could not be installed: " + e.getMessage()); }
        card = TaskbarCard.free();
        card.onRefresh(page -> FreePages.reread(gauges, page));
        card.modeLabel(() -> { var o = FreeOverall.of(gauges.snapshot(), limits.freeFiveLimit(), limits.freeWeekLimit(), Instant.now()); return o.accounts() == 0 ? "free" : o.withRoom() + "/" + o.accounts() + " room"; });
        card.start();
        push();
    }

    void push() {
        var c = card;
        if (c == null) return;
        var rows = gauges.snapshot();
        var now = Instant.now();
        c.updateFree(accounts(FreePane.entries(gauges, now, ZoneId.systemDefault()), now), overall(rows, limits.freeFiveLimit(), limits.freeWeekLimit(), now));
    }


    void stop() { if (card != null) card.stop(); card = null; }

    // ---- what the card draws, from the gauge rows

    /**
     * One page per free account, every one of them and in the order of {@link FreeStates} (usable first): its five-hour usage with the reset of that window, and its
     * weekly usage (a free account shows no weekly reset). An account never read has a page that says "no data"; one whose restore time is not known has no bars.
     */
    static List<AccountUsage> accounts(List<FreeStates.Entry> entries, Instant now) {
        return entries.stream().map(e -> {
            var r = e.row() == null ? FreeStates.blank(e.id()) : e.row();
            var reset = r.resetsAt() != null && r.resetsAt() <= now.getEpochSecond();
            var data = new UsageData();
            if (r.five() != null) data.fiveHour(new Usage().utilization(reset ? 0 : r.five() * 100).resetsAt(reset ? null : at(r.resetsAt())));
            if (r.seven() != null) data.sevenDay(new Usage().utilization(r.seven() * 100));
            var credential = new CCAPICredential().id(r.id()).email(r.email()).tier(0).active(true);
            return new AccountUsage(credential, r.five() == null && r.seven() == null ? null : data).fetchedAt(r.read() == null ? OffsetDateTime.now() : OffsetDateTime.ofInstant(r.read(), ZoneOffset.UTC));
        }).toList();
    }

    /** The ALL page: the average usage of the free accounts, its five-hour reset set so that the share of time gone is the average one, and the average weekly usage. */
    static AccountUsage overall(List<FreeGauges.Row> rows, double fiveLimit, double weekLimit, Instant now) {
        var o = FreeOverall.of(rows, fiveLimit, weekLimit, now);
        var data = new UsageData();
        if (!Double.isNaN(o.five())) {
            var left = Math.round((100 - Math.max(0, Math.min(100, Double.isNaN(o.fiveTime()) ? 0 : o.fiveTime()))) / 100 * FreeOverall.FIVE_HOURS);
            data.fiveHour(new Usage().utilization(o.five() * 100).resetsAt(OffsetDateTime.ofInstant(now.plusSeconds(left), ZoneOffset.UTC)));
        }
        if (!Double.isNaN(o.week())) data.sevenDay(new Usage().utilization(o.week() * 100));
        return new AccountUsage(new CCAPICredential().id("ALL").name("All free accounts").active(true).tier(o.accounts()), data);
    }

    static OffsetDateTime at(Long epochSeconds) { return epochSeconds == null ? null : OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC); }
}
