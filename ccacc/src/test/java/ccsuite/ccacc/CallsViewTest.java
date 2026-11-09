package ccsuite.ccacc;

import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** The calls report as the window shows it: the same lines the command prints, one function for both. */
class CallsViewTest {

    @Test void theReportListsEachJobAndWhatWentWrong(@TempDir Path dir) throws Exception {
        var log = dir.resolve("calls.jsonl");
        Files.write(log, CallsReportTest.LINES);
        var lines = CallsView.lines(List.of(log), null, null, "job", Duration.ofSeconds(60), Optional.empty());
        var text = String.join("\n", lines);
        assertTrue(text.contains("A") && text.contains("B"), text);
        assertTrue(text.contains("RATE_LIMITED"), "a failed call is named: " + text);
    }

    @Test void aJobAndAWindowOfTimeNarrowItDown(@TempDir Path dir) throws Exception {
        var log = dir.resolve("calls.jsonl");
        Files.write(log, CallsReportTest.LINES);
        var onlyB = String.join("\n", CallsView.lines(List.of(log), "B", null, "job", Duration.ofSeconds(60), Optional.empty()));
        assertTrue(onlyB.contains("B"));
        assertFalse(onlyB.contains("RATE_LIMITED"), "job A's failure is not in job B's report: " + onlyB);
    }

    @Test void noLogYetSaysSoAndByAccountGroupsByAccount(@TempDir Path dir) throws Exception {
        var none = CallsView.lines(List.of(dir.resolve("missing.jsonl")), null, null, "job", Duration.ofSeconds(60), Optional.empty());
        assertTrue(none.getFirst().startsWith("No calls logged yet"), none.toString());
        var log = dir.resolve("calls.jsonl");
        Files.write(log, CallsReportTest.LINES);
        var byAccount = String.join("\n", CallsView.lines(List.of(log), null, null, "account", Duration.ofSeconds(60), Optional.empty()));
        assertTrue(byAccount.contains("F1") && byAccount.contains("F2"), byAccount);
    }
}
