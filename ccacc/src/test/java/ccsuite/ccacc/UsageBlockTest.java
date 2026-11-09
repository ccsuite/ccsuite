package ccsuite.ccacc;

import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.Usage;
import ccsuite.base.model.UsageData;
import ccsuite.usagetracker.model.AccountUsage;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** A cached value says it is cached: a reading older than three minutes shows its age beside the account's name. */
class UsageBlockTest {

    static AccountUsage page(String id, long minutesOld) {
        var data = new UsageData().fiveHour(new Usage().utilization(10).resetsAt(OffsetDateTime.now().plusHours(2))).sevenDay(new Usage().utilization(20).resetsAt(OffsetDateTime.now().plusDays(2)));
        return new AccountUsage(new CCAPICredential().id(id).tier(5), data).fetchedAt(OffsetDateTime.now().minusMinutes(minutesOld));
    }

    static String stateOf(AccountUsage a, String extra) throws Exception {
        MainWindowTest.toolkit();
        return MainWindowTest.onFx(() -> { var b = new UsageBlock(a.credential().id(), "#1e88e5"); b.update(a, extra); return b.state.getText(); });
    }

    @Test void aFreshReadingShowsNoAge() throws Exception {
        assertEquals("", stateOf(page("A", 1), null));
    }

    @Test void anOldReadingShowsItsAgeAsCached() throws Exception {
        var s = stateOf(page("A", 78), null);
        assertTrue(s.contains("1h18m old") && s.contains("cached"), s);
    }

    @Test void aStateThatAlreadyNamesTheAgeIsNotRepeatedAndAllNeverShowsOne() throws Exception {
        assertEquals("available, read 5 h ago by ledger", stateOf(page("F", 300), "available, read 5 h ago by ledger"));
        assertEquals("", stateOf(page("ALL", 300), null));
    }
}
