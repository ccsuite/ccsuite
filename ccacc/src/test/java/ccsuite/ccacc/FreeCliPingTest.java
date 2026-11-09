package ccsuite.ccacc;

import module java.base;
import ccsuite.base.model.CCAPICredential;
import ccsuite.webclient.FreeGauge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** {@code ccacc free --ping ID...}: a forced ping of the named accounts only, and a plain statement of what came back. */
class FreeCliPingTest {

    static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    static final ZoneId UTC = ZoneOffset.UTC;

    static CCAPICredential account(String id) { return new CCAPICredential().id(id).email(id + "@example.org").tier(0).key("k").orgId("o"); }

    final List<String> probed = new ArrayList<>();
    final Map<String, FreeGauge.Reading> answers = new HashMap<>();
    final Map<String, String> failing = new HashMap<>();

    FreeGauges gauges(Path dir, CCAPICredential... accounts) {
        var list = List.of(accounts);
        FreeGauges.Probe probe = (c, m) -> {
            probed.add(c.id());
            if (failing.containsKey(c.id())) throw new IOException(failing.get(c.id()));
            return answers.get(c.id());
        };
        return new FreeGauges(() -> list, probe, dir.resolve("readings"));
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

    @Test void optionsTakeTheNamedAccountsUpToTheNextOption() {
        assertEquals(List.of("IKO"), FreeCli.Options.parse(List.of("--ping", "IKO")).ping());
        assertEquals(List.of("IKO", "ADA", "S@x"), FreeCli.Options.parse(List.of("--ping", "IKO,ADA", "S@x", "--json")).ping());
        assertTrue(FreeCli.Options.parse(List.of("--refresh")).ping().isEmpty());
    }

    @Test void onlyTheNamedAccountIsPingedEvenWhenItIsExhaustedAndItsRestoreTimeIsAhead(@TempDir Path dir) throws Exception {
        var g = gauges(dir, account("IKO"), account("ADA"));
        g.seed("IKO", 1.0, 0.1, NOW.getEpochSecond() + 3600, NOW.minus(Duration.ofHours(1)));
        answers.put("IKO", new FreeGauge.Reading(1.0, null, "exceeded_limit", NOW.getEpochSecond() + 3600, null, 0, 0));
        var report = FreeCli.ping(g, List.of("iko"), new FreeLedger(dir.resolve("ledger")), NOW, UTC);
        assertEquals(List.of("IKO"), probed, "ADA is not touched; the id is not case sensitive");
        assertTrue(report.ok());
        assertTrue(report.lines().get(0).startsWith("pinged IKO at ") && report.lines().get(0).contains("was: exhausted, resets at 13:00"), report.lines().get(0));
        assertEquals("  answered: status exceeded_limit", report.lines().get(1));
        assertTrue(report.lines().get(2).contains("IKO") && report.lines().get(2).contains("exhausted, resets at 13:00"), report.lines().get(2));
        var ledger = pingLines(dir);
        assertEquals(1, ledger.size());
        assertTrue(ledger.get(0).contains("\"account\":\"IKO\"") && ledger.get(0).contains("forced from the command line"), ledger.get(0));
    }

    @Test void aRefusedSessionKeyIsSaidInWordsAndFailsTheCommand(@TempDir Path dir) throws Exception {
        var g = gauges(dir, account("IKO"));
        failing.put("IKO", "gauge: create chat failed, HTTP 403 {\"type\":\"error\",\"error\":{\"type\":\"permission_error\",\"message\":\"Invalid authorization\",\"details\":{\"error_code\":\"account_session_invalid\"}}}");
        var report = FreeCli.ping(g, List.of("IKO"), new FreeLedger(dir.resolve("ledger")), NOW, UTC);
        assertFalse(report.ok());
        assertTrue(report.lines().get(1).contains("FAILED: HTTP 403 creating the chat permission_error account_session_invalid: Invalid authorization (the server no longer accepts this session key: it has to be replaced)"), report.lines().get(1));
        assertTrue(pingLines(dir).get(0).contains("account_session_invalid"));
    }

    @Test void anAccountWhoseWindowHasResetComesBackAvailable(@TempDir Path dir) {
        var g = gauges(dir, account("ADA").active(false));
        answers.put("ADA", new FreeGauge.Reading(0.01, 0.13, "within_limit", Instant.now().getEpochSecond() + 5 * 3600, "claude-haiku-5-5", 1, 1));
        var report = FreeCli.ping(g, List.of("ADA"), new FreeLedger(dir.resolve("ledger")), NOW, UTC);
        assertTrue(report.ok());
        assertTrue(report.lines().get(0).contains("was: restore time not known yet"), report.lines().get(0));
        assertTrue(report.lines().get(2).contains("99 points left"), report.lines().get(2));
    }

    @Test void anUnknownIdIsAnErrorThatNamesTheFreeAccountsAndPingsNothing(@TempDir Path dir) {
        var g = gauges(dir, account("IKO"), account("ADA"));
        var report = FreeCli.ping(g, List.of("IKO", "NOPE"), new FreeLedger(dir.resolve("ledger")), NOW, UTC);
        assertFalse(report.ok());
        assertEquals("no free account named NOPE; the free accounts are IKO, ADA", report.lines().get(0));
        assertTrue(probed.isEmpty());
    }

    @Test void theAppAnswersAPingRequestFromItsOwnTable(@TempDir Path dir) {
        var g = gauges(dir, account("IKO"));
        answers.put("IKO", new FreeGauge.Reading(0.2, 0.0, "within_limit", Instant.now().getEpochSecond() + 3600, "m", 1, 1));
        var ok = FreeCli.serve(List.of("free", "--ping", "IKO"), g, new LaneSettings(), new FreeLedger(dir.resolve("ledger")));
        assertTrue(ok.ok() && ok.message().startsWith("pinged IKO at "), ok.message());
        var bad = FreeCli.serve(List.of("free", "--ping", "ZZZ"), g, new LaneSettings(), new FreeLedger(dir.resolve("ledger")));
        assertFalse(bad.ok());
    }
}
