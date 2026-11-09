package ccsuite.usagetracker.ui;

import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.Usage;
import ccsuite.base.model.UsageData;
import ccsuite.usagetracker.model.AccountUsage;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

class UsageDetailsTest {

    static AccountUsage paid(double five, double week) {
        var now = OffsetDateTime.now();
        var data = new UsageData()
            .fiveHour(new Usage().utilization(five).resetsAt(now.plusHours(3)))        // 40% of the window gone
            .sevenDay(new Usage().utilization(week).resetsAt(now.plusDays(4)));        // about 43% of the week gone
        return new AccountUsage(new CCAPICredential().id("ANN").name("Ann").email("ann@example.org").tier(5), data);
    }

    @Test void aPaidAccountShowsEveryFigureAndItsCoolDown() {
        var t = UsageDetails.text(paid(50, 70));
        assertTrue(t.contains("Account: ANN"), t);
        assertTrue(t.contains("5-hour window"), t);
        assertTrue(t.contains("Usage: 50.0%"), t);
        assertTrue(t.contains("Time elapsed: 40"), t);
        assertTrue(t.contains("Ratio: 125%"), t);
        assertTrue(t.contains("Weekly window"), t);
        assertTrue(t.contains("Usage: 70.0%"), t);
        assertTrue(t.matches("(?s).*Cool-down: \\d+d(\\d+h)? unused.*"), t);
    }

    @Test void aWeeklyRatioUnder100NeedsNoCoolDown() {
        var t = UsageDetails.text(paid(10, 20));
        assertTrue(t.contains("Cool-down: none"), t);
    }

    @Test void aFreeAccountSaysItHasNoWeeklyResetAndInventsNothing() {
        var data = new UsageData().fiveHour(new Usage().utilization(30).resetsAt(OffsetDateTime.now().plusHours(2))).sevenDay(new Usage().utilization(12));
        var t = UsageDetails.text(new AccountUsage(new CCAPICredential().id("QX").tier(0), data));
        assertTrue(t.contains("not known (a free account has no weekly reset)"), t);
        assertTrue(t.contains("Cool-down: -"), t);
        assertFalse(t.contains("Cool-down: none"), t);
    }

    @Test void theAllPageIsNamedAndCountsItsAccounts() {
        var data = new UsageData().fiveHour(new Usage().utilization(20).resetsAt(OffsetDateTime.now().plusHours(1)));
        var t = UsageDetails.text(new AccountUsage(new CCAPICredential().id("ALL").name("All accounts").tier(3), data));
        assertTrue(t.startsWith("All accounts (average of 3)"), t);
        assertTrue(t.contains("Weekly window\n  no reading"), t);
    }

    @Test void noPageSaysNoData() { assertEquals("no data", UsageDetails.text(null)); }

    @Test void anOldReadingSaysHowOldItIsAndAnExhaustedAccountSaysWhenItComesBack() {
        var a = paid(50, 99);
        a.fetchedAt(OffsetDateTime.now().minusMinutes(78));
        var t = UsageDetails.text(a);
        assertTrue(t.contains("Reading: 1h18m old"), t);
        assertTrue(t.contains("Back: "), "the week at 99% is used up, so the restore time is its reset: " + t);
    }

    @Test void aFreshReadingSaysNothingAboutAge() {
        var t = UsageDetails.text(paid(50, 20));
        assertFalse(t.contains("Reading:"), t);
        assertFalse(t.contains("Back:"), "nothing is used up: " + t);
    }

    @Test void aPaidWeekThatHasResetIsNotDescribedAsAFreeAccount() {
        var data = new UsageData().fiveHour(new Usage().utilization(0)).sevenDay(new Usage().utilization(0));
        var t = UsageDetails.text(new AccountUsage(new CCAPICredential().id("NSA").tier(5), data));
        assertFalse(t.contains("free account"), t);
        assertTrue(t.contains("has reset"), t);
    }
}
