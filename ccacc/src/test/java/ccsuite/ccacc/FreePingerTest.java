package ccsuite.ccacc;

import module java.base;
import ccsuite.base.model.CCAPICredential;
import ccsuite.webclient.FreeGauge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** The ping round against a fake probe: who is pinged, the pause between pings, the ledger line, the retry at the next round, the restart from the ledger. */
class FreePingerTest {

    static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

    static CCAPICredential account(String id) { return new CCAPICredential().id(id).email(id + "@example.org").tier(0).key("k").orgId("o"); }

    final List<String> probed = new ArrayList<>();
    final List<Duration> pauses = new ArrayList<>();
    final Map<String, String> failing = new HashMap<>();

    FreePinger pinger(Path dir, List<CCAPICredential> accounts) {
        FreeGauges.Probe probe = (c, m) -> {
            probed.add(c.id());
            if (failing.containsKey(c.id())) throw new IOException(failing.get(c.id()));
            return new FreeGauge.Reading(0.02, 0.0, "within_limit", Instant.now().getEpochSecond() + 5 * 3600, m, 1, 1);
        };
        var gauges = new FreeGauges(() -> accounts, probe, dir.resolve("readings"));
        return new FreePinger(gauges, () -> accounts, LaneSettings::new, new FreeLedger(dir.resolve("ledger")), pauses::add);
    }

    static List<String> pingLines(Path dir) throws IOException {
        var ledger = dir.resolve("ledger");
        if (!Files.isDirectory(ledger)) return List.of();
        try (var files = Files.list(ledger)) {
            var out = new ArrayList<String>();
            for (var f : files.filter(p -> p.getFileName().toString().startsWith("pings-")).toList()) out.addAll(Files.readAllLines(f));
            return out;
        }
    }

    @Test void everyAccountNeverReadIsPingedOnceSpreadOutAndLogged(@TempDir Path dir) throws Exception {
        var p = pinger(dir, List.of(account("A"), account("B"), account("C")));
        var sent = p.round(Instant.now());
        assertEquals(List.of("A", "B", "C"), probed);
        assertEquals(3, sent.size());
        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(5)), pauses, "a pause between two pings, none before the first");
        var lines = pingLines(dir);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("\"account\":\"A\"") && lines.get(0).contains("\"why\":\"never read\"") && lines.get(0).contains("\"u5h\":0.02"));
        assertEquals("ping", p.gauges.sources.get("A"));
    }

    @Test void aSecondRoundRightAfterPingsNobody(@TempDir Path dir) throws Exception {
        var p = pinger(dir, List.of(account("A"), account("B")));
        p.round(Instant.now());
        probed.clear();
        assertTrue(p.round(Instant.now()).isEmpty());
        assertTrue(probed.isEmpty());
        assertEquals(2, pingLines(dir).size());
    }

    @Test void aFailedPingIsRetriedAtTheNextRoundAndNotInALoop(@TempDir Path dir) throws Exception {
        failing.put("B", "Failed to send message: HTTP 500 - upstream error");
        var p = pinger(dir, List.of(account("A"), account("B")));
        p.round(Instant.now());
        assertEquals(List.of("A", "B"), probed, "one attempt in the round, no retry loop");
        probed.clear();
        failing.clear();
        p.round(Instant.now());
        assertEquals(List.of("B"), probed, "only the failed one, at the next round");
        var lines = pingLines(dir);
        assertTrue(lines.get(1).contains("\"problem\":\"HTTP 500 - upstream error\"") && lines.get(1).contains("\"u5h\":null"));
        assertTrue(lines.get(2).contains("the last ping failed"));
    }

    @Test void nothingIsPingedWithoutEligibleFreeAccounts(@TempDir Path dir) throws Exception {
        var none = pinger(dir, List.of());
        assertTrue(none.round(Instant.now()).isEmpty());
        var off = pinger(dir, List.of(account("B").watchOnly(true), account("C").tier(1)));
        assertTrue(off.round(Instant.now()).isEmpty());
        assertTrue(probed.isEmpty());
        assertTrue(pingLines(dir).isEmpty());
    }

    // (a) end to end: flag off, 100%, restore time already passed: pinged, and the reading that comes back shows it available
    @Test void anAccountWithTheFlagOffAndAPassedRestoreTimeIsPingedAndComesBackAvailable(@TempDir Path dir) {
        var a = account("A").active(false);
        var p = pinger(dir, List.of(a));
        var past = Instant.now().getEpochSecond() - 600;
        p.gauges.seed("A", 1.0, 0.1, past, Instant.now().minus(Duration.ofMinutes(5)));
        p.round(Instant.now());
        assertEquals(List.of("A"), probed);
        assertEquals(FreeStates.Kind.AVAILABLE, FreeStates.entry(a, p.gauges.rows.get("A"), false, Instant.now(), ZoneOffset.UTC).kind());
    }

    @Test void anAccountWithAnUnknownRestoreTimeIsPingedNowAndThenOnlyHourly(@TempDir Path dir) {
        var p = pinger(dir, List.of(account("A")));
        p.round(Instant.now());
        assertEquals(List.of("A"), probed, "never read: the ping starts it");
        var n = Instant.now();
        p.gauges.rows.put("A", new FreeGauges.Row("A", "a", 1.0, null, "exceeded_limit", null, n.minus(Duration.ofMinutes(30)), null, null, 0));
        p.round(n);
        assertEquals(List.of("A"), probed, "30 minutes since the last probe: not again");
        p.gauges.rows.put("A", new FreeGauges.Row("A", "a", 1.0, null, "exceeded_limit", null, n.minus(Duration.ofMinutes(61)), null, null, 0));
        p.round(n);
        assertEquals(List.of("A", "A"), probed, "61 minutes: probed");
    }

    @Test void aReadingOfAServedCallCountsAsRecent(@TempDir Path dir) {
        var p = pinger(dir, List.of(account("A"), account("B")));
        p.gauges.observe("A", 0.30, 0.01, Instant.now().getEpochSecond() + 3 * 3600);
        p.round(Instant.now());
        assertEquals(List.of("B"), probed, "A is in use and its reading is fresh");
        assertEquals("call", p.gauges.sources.get("A"));
    }

    @Test void theLedgersRecentReadingsAreTakenInAtStartSoARestartDoesNotPingThem(@TempDir Path dir) {
        var accounts = List.of(account("A"), account("B"), account("C"));
        var p = pinger(dir, accounts);
        var t = Instant.now().minus(Duration.ofMinutes(10));
        var reset = Instant.now().getEpochSecond() + 4 * 3600;
        p.ledger.append(new FreeLedger.Call(t, "A", "claude-sonnet-5-5", 100, 30, "new", 200, 900, 0.1, 0.0, reset, 0.12, 0.0, reset, 2, 2.0, true, "s"));
        p.ledger.append(new FreeLedger.Correction(t, "B", "probe", 3.0, 4.0, 1.0, 0.0, reset));
        p.ledger.append(new FreeLedger.Correction(Instant.now().minus(Duration.ofHours(3)), "C", "probe", 3.0, 4.0, 1.0, 0.0, reset));
        p.seed(Instant.now());
        p.round(Instant.now());
        assertEquals(List.of("C"), probed, "A (a call) and B (a probe) are recent in the ledger; C's reading is three hours old");
        assertEquals("ledger", p.gauges.sources.get("A"));
        assertEquals(0.04, p.gauges.rows.get("B").five(), 1e-9, "a correction holds points, the table a fraction");
    }

    @Test void aPingReadingInTheLedgerIsTakenInAtStartSoARestartReachesTheAccountsNotYetPinged(@TempDir Path dir) {
        var accounts = List.of(account("A"), account("B"), account("C"), account("D"));
        var p = pinger(dir, accounts);
        var reset = Instant.now().getEpochSecond() + 4 * 3600;
        // A and B were pinged a few minutes ago (a ping that agrees with the estimate leaves no call and no correction, only its ping line); a failed ping of C left no reading; D was never pinged
        p.ledger.append(new FreeLedger.Ping(Instant.now().minus(Duration.ofMinutes(5)), "A", "never read", 0.02, 0.1, reset, "within_limit", null));
        p.ledger.append(new FreeLedger.Ping(Instant.now().minus(Duration.ofMinutes(4)), "B", "never read", 0.02, 0.1, reset, "within_limit", null));
        p.ledger.append(new FreeLedger.Ping(Instant.now().minus(Duration.ofMinutes(3)), "C", "never read", null, null, null, null, "request failed: Connection was reset"));
        p.seed(Instant.now());
        p.round(Instant.now());
        assertEquals(List.of("C", "D"), probed, "a restart must not ping A and B again before it gets to C and D");
        assertEquals("ledger", p.gauges.sources.get("A"));
    }

    @Test void aLedgerReadingNeverReplacesANewerRow(@TempDir Path dir) {
        var p = pinger(dir, List.of(account("A")));
        p.gauges.observe("A", 0.5, 0.0, Instant.now().getEpochSecond() + 3600);
        p.gauges.seed("A", 0.1, 0.0, null, Instant.now().minus(Duration.ofMinutes(5)));
        assertEquals(0.5, p.gauges.rows.get("A").five());
        assertEquals("call", p.gauges.sources.get("A"));
    }

    @Test void anExhaustedAccountKnownOnlyFromAnOlderLedgerReadingIsNotPingedBeforeItsReset(@TempDir Path dir) {
        var p = pinger(dir, List.of(account("IKO"), account("B")));
        var reset = Instant.now().getEpochSecond() + 2 * 3600;
        p.ledger.append(new FreeLedger.Correction(Instant.now().minus(Duration.ofHours(3)), "IKO", "probe", 90.0, 100.0, 10.0, 2.0, reset));
        p.seed(Instant.now());
        p.round(Instant.now());
        assertEquals(List.of("B"), probed, "IKO was seen at 100% three hours ago and its window resets in two hours: it is not 'never read'");
    }

    @Test void aPingThatFailsWithHttp429IsAFaultNotAnExhaustedReadingAndIsTriedAgainAtTheNextRound(@TempDir Path dir) throws Exception {
        failing.put("A", "gauge: HTTP 429 {\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"Rate limited. Please try again later.\"}}");
        var p = pinger(dir, List.of(account("A")));
        p.gauges.seed("A", 0.30, 0.02, Instant.now().getEpochSecond() + 3600, Instant.now().minus(Duration.ofHours(2)));
        p.round(Instant.now());
        var row = p.gauges.rows.get("A");
        assertEquals(0.30, row.five(), "the last reading is kept: a failed ping says nothing about the account's room");
        assertNotEquals("exceeded_limit", row.status());
        assertEquals("HTTP 429 rate_limit_error: Rate limited. Please try again later.", row.problem());
        probed.clear();
        assertEquals(List.of("A"), p.round(Instant.now()).stream().map(FreeLedger.Ping::account).toList(), "a fault is tried again at the next round");
    }

    @Test void aSpentAccountIsNotPingedBeforeItsReset(@TempDir Path dir) {
        var p = pinger(dir, List.of(account("A")));
        p.gauges.seed("A", 1.17, 0.02, Instant.now().getEpochSecond() + 2 * 3600, Instant.now().minus(Duration.ofHours(2)));
        p.round(Instant.now());
        assertTrue(probed.isEmpty());
    }
}
