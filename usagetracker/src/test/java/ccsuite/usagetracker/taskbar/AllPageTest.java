package ccsuite.usagetracker.taskbar;

import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.UsageData;
import ccsuite.usagetracker.model.AccountUsage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AllPageTest {

    static AccountUsage account(String id, double five, long fiveSecsLeft, double week, long weekSecsLeft) {
        var d = new UsageData().fiveHour(AllPage.window(five, 100 - fiveSecsLeft * 100.0 / AllPage.FIVE_HOURS, AllPage.FIVE_HOURS))
                .sevenDay(AllPage.window(week, 100 - weekSecsLeft * 100.0 / AllPage.WEEK, AllPage.WEEK));
        return new AccountUsage(new CCAPICredential().id(id), d);
    }

    @Test void theAveragesOfTwoAccountsBecomeOnePageNamedAll() {
        var half5 = AllPage.FIVE_HOURS / 2;
        var half7 = AllPage.WEEK / 2;
        var list = List.of(account("a", 40, half5, 90, half7), account("b", 20, half5, 50, half7));
        var total = AllPage.total(list);
        assertEquals(30, total.fiveHourUsage(), 0.1);
        assertEquals(50, total.fiveHourTime(), 0.1);
        assertEquals(70, total.weekUsage(), 0.1);
        var page = AllPage.page(total, list.size());
        assertEquals("ALL", page.credential().id());
        assertEquals(2, page.credential().tier());
        assertEquals(30, page.getFiveHourUsagePercent(), 0.1);
        assertEquals(50, page.getFiveHourTimeElapsedPercent(), 0.5);
        assertEquals(70, page.usageData().sevenDay().utilization(), 0.1);
    }

    @Test void oneAccountOrNoDataGivesNoPage() {
        var one = List.of(account("a", 40, 100, 90, 100));
        assertNull(AllPage.page(AllPage.total(one), 1));
        var blank = List.of(new AccountUsage(new CCAPICredential().id("x"), null), new AccountUsage(new CCAPICredential().id("y"), null));
        assertNull(AllPage.page(AllPage.total(blank), 2));
    }
}
