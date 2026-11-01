package ccsuite.oauthswap.watch;

import ccsuite.oauthclient.AccountUsage;
import ccsuite.oauthswap.watch.BalancePlanner.Policy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BalancePlannerTest {

    static final Instant NOW = Instant.parse("2026-11-01T02:00:00Z");
    final BalancePlanner planner = new BalancePlanner(5.0, 0.01, 50.0, Duration.ofHours(24));

    static final Policy SHARED = new Policy(95.0, false), PRIMARY = new Policy(95.0, true);

    /**
     * An account's reading: 5-hour use and how far into its 5 hours it is, week use and how many hours of the week are
     * left (the week has 168).
     */
    static AccountUsage usage(double fivePct, double fiveElapsedPct, double weekPct, double weekHoursLeft) {
        var fiveReset = NOW.plusSeconds((long) ((100 - fiveElapsedPct) / 100 * 5 * 3600));
        var weekReset = NOW.plusSeconds((long) (weekHoursLeft * 3600));
        return new AccountUsage(fivePct, iso(fiveReset), weekPct, iso(weekReset), (168 - weekHoursLeft) / 168 * 100);
    }

    static String iso(Instant i) { return i.atOffset(ZoneOffset.UTC).toString(); }

    static Map<String, AccountUsage> accounts(Object... idAndUsage) {
        var m = new LinkedHashMap<String, AccountUsage>();
        for (var i = 0; i < idAndUsage.length; i += 2) m.put((String) idAndUsage[i], (AccountUsage) idAndUsage[i + 1]);
        return m;
    }

    Optional<BalancePlanner.Plan> plan(String active, Map<String, AccountUsage> u, Map<String, Policy> p) {
        return planner.plan(active, u, p, NOW);
    }

    // ---- week-end burn

    @Test void movesToTheAccountWhoseWeekEndsWithinADayAndStillHasQuota() {
        var u = accounts("A", usage(10, 50, 40, 100), "B", usage(0, 0, 30, 10));
        var p = plan("A", u, Map.of("A", SHARED, "B", PRIMARY)).orElseThrow();
        assertEquals("B", p.targetId());
        assertTrue(p.reason().startsWith("week-end burn"), p.reason());
    }

    @Test void theWeekThatEndsFirstIsBurnedFirst() {
        var u = accounts("A", usage(10, 50, 40, 100), "B", usage(0, 0, 30, 20), "C", usage(0, 0, 30, 5));
        assertEquals("C", plan("A", u, Map.of("B", PRIMARY, "C", PRIMARY)).orElseThrow().targetId());
    }

    @Test void staysOnTheBurningAccountEvenWhenAnotherHasALowerPace() {
        // A ends in 6 h at 80% (pace ~83%); B is fresh at the start of its week, which the pace rebalance would prefer.
        var u = accounts("A", usage(20, 50, 80, 6), "B", usage(0, 0, 1, 160));
        var p = plan("A", u, Map.of("A", PRIMARY, "B", SHARED)).orElseThrow();
        assertFalse(p.moves("A"), p.toString());
    }

    @Test void aPrimaryIsBurnedPastItsLimitToAHundred() {
        var u = accounts("A", usage(20, 50, 97, 6), "B", usage(0, 0, 1, 160));
        assertFalse(plan("A", u, Map.of("A", PRIMARY)).orElseThrow().moves("A"));
    }

    @Test void anAccountThatIsNotPrimaryIsBurnedOnlyToItsLimit() {
        // A at 95% of a 95% limit: nothing left to burn, so the pace rebalance decides, and B has the lower pace.
        var u = accounts("A", usage(20, 50, 95, 6), "B", usage(0, 0, 1, 160));
        var p = plan("A", u, Map.of("A", SHARED, "B", SHARED)).orElseThrow();
        assertEquals("B", p.targetId());
        assertTrue(p.reason().startsWith("rebalance"), p.reason());
    }

    @Test void noBurnWhenTheWeekEndsLaterThanTheWindow() {
        var u = accounts("A", usage(10, 50, 40, 100), "B", usage(0, 0, 30, 30));
        assertTrue(planner.burn("A", u, Map.of("B", PRIMARY), NOW).isEmpty());
    }

    @Test void noBurnOfAnAccountWhose5HoursAreUsedUp() {
        var u = accounts("A", usage(10, 50, 40, 100), "B", usage(97, 90, 30, 10));
        assertTrue(planner.burn("A", u, Map.of("B", PRIMARY), NOW).isEmpty());
    }

    @Test void aSharedAccountIsNotBurnedIntoA5HourSpike() {
        // B's week ends soon, but its 5 hours are already 60% used: moving everyone onto it would lock its owner out.
        var u = accounts("A", usage(10, 50, 40, 100), "B", usage(60, 40, 30, 10));
        assertTrue(planner.burn("A", u, Map.of("B", SHARED), NOW).isEmpty());
    }

    // ---- 5-hour guard

    @Test void movesOffASharedAccountSpentFasterThanItsFiveHoursPass() {
        // A: 60% of its 5 hours used in 40% of them.
        var u = accounts("A", usage(60, 40, 30, 100), "B", usage(5, 50, 50, 100));
        var p = plan("A", u, Map.of("A", SHARED, "B", SHARED)).orElseThrow();
        assertEquals("B", p.targetId());
        assertTrue(p.reason().startsWith("5-hour guard"), p.reason());
    }

    @Test void theGuardIgnoresAPrimaryAccount() {
        var u = accounts("A", usage(60, 40, 30, 100), "B", usage(5, 50, 50, 100));
        assertTrue(planner.guard("A", u, Map.of("A", PRIMARY, "B", SHARED), NOW).isEmpty());
    }

    @Test void noGuardWhenMoreThanHalfIsUsedButSlowerThanTimePasses() {
        var u = accounts("A", usage(60, 80, 30, 100), "B", usage(5, 50, 50, 100));
        assertTrue(planner.guard("A", u, Map.of("A", SHARED, "B", SHARED), NOW).isEmpty());
    }

    @Test void theGuardDoesNotMoveOntoAnotherAccountPastHalfItsFiveHours() {
        var u = accounts("A", usage(60, 40, 30, 100), "B", usage(55, 90, 10, 100));
        assertTrue(planner.guard("A", u, Map.of("A", SHARED, "B", SHARED), NOW).isEmpty());
    }

    @Test void thePaceRebalanceDoesNotSendLoadIntoAnAccountPastHalfItsFiveHours() {
        // B has the lowest weekly pace, but its 5 hours are 70% used: no move.
        var u = accounts("A", usage(10, 50, 80, 50), "B", usage(70, 95, 10, 100));
        assertTrue(plan("A", u, Map.of("A", SHARED, "B", SHARED)).isEmpty());
    }

    @Test void thePaceRebalanceStillWorksAsBefore() {
        var u = accounts("A", usage(10, 50, 80, 50), "B", usage(10, 50, 10, 100));
        assertEquals("B", plan("A", u, Map.of("A", SHARED, "B", SHARED)).orElseThrow().targetId());
    }

    @Test void nothingWithoutAnActiveAccount() {
        assertTrue(plan(null, accounts("B", usage(0, 0, 10, 10)), Map.of()).isEmpty());
    }

    @Test void fiveHourElapsedComesFromTheResetTime() {
        assertEquals(40.0, usage(60, 40, 30, 100).fiveHourElapsedPct(NOW), 0.01);
        assertEquals(Duration.ofHours(10), usage(0, 0, 30, 10).weekLeft(NOW));
    }
}
