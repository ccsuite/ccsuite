package ccsuite.oauthswap.watch;

import org.junit.jupiter.api.Test;
import ccsuite.oauthclient.AccountUsage;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SwitchPlannerTest {

    private final SwitchPlanner planner = new SwitchPlanner(100.0, 5.0);

    private static AccountUsage util(double u) { return new AccountUsage(u, null, 0.0, null, null); }

    /** 5h utilization and 7d utilization set independently (no time-elapsed). */
    private static AccountUsage wk(double fiveH, double sevenD) {
        return new AccountUsage(fiveH, null, sevenD, null, null);
    }

    @Test void noneWhenBelowLimit() {
        var d = planner.decide("A", 95, Map.of("A", util(50), "B", util(10)));
        assertEquals(Decision.Kind.NONE, d.kind());
    }

    @Test void promptAtSoftLimit() {
        var d = planner.decide("A", 95, Map.of("A", util(96), "B", util(20)));
        assertEquals(Decision.Kind.PROMPT, d.kind());
        assertEquals("B", d.targetId());
    }

    @Test void autoSwitchWhenDepleted() {
        var d = planner.decide("A", 95, Map.of("A", util(100), "B", util(20)));
        assertEquals(Decision.Kind.AUTO_SWITCH, d.kind());
        assertEquals("B", d.targetId());
    }

    @Test void noTargetWhenOthersAlsoExhausted() {
        var d = planner.decide("A", 95, Map.of("A", util(100), "B", util(99)));
        assertEquals(Decision.Kind.NONE, d.kind());
    }

    @Test void picksHighestHeadroomTarget() {
        var d = planner.decide("A", 95, Map.of("A", util(97), "B", util(40), "C", util(10)));
        assertEquals(Decision.Kind.PROMPT, d.kind());
        assertEquals("C", d.targetId());
    }

    @Test void targetPrefersMostWeeklyHeadroomOver5hHeadroom() {
        // B has more 5h headroom (90) but little weekly left (20); C has more weekly left (90).
        var d = planner.decide("A", 95,
                Map.of("A", util(96), "B", wk(10, 80), "C", wk(50, 10)));
        assertEquals(Decision.Kind.PROMPT, d.kind());
        assertEquals("C", d.targetId());
    }

    @Test void unknownActiveUsageIsNone() {
        var m = new HashMap<String, AccountUsage>();
        m.put("A", null);
        m.put("B", util(10));
        var d = planner.decide("A", 95, m);
        assertEquals(Decision.Kind.NONE, d.kind());
    }
}
