package ccsuite.usagetracker.model;

import ccsuite.base.model.CCAPICredential;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** An account the usage endpoint says nothing about (it is used up) still has its last reading in the log, with the time it comes back and how old the reading is. */
class LastReadingsTest {

    static String line(String t, String account, double u5, double u7, String r5, String r7) {
        return "{\"t\":\"%s\",\"account\":\"%s\",\"active\":false,\"u5h\":%s,\"u7d\":%s,\"reset5h\":\"%s\",\"reset7d\":\"%s\"}\n".formatted(t, account, u5, u7, r5, r7);
    }

    @Test void theLastLineOfEachAccountWinsAcrossDays(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("2026-11-06.jsonl"), line("2026-11-06T22:00:00Z", "NSA", 5, 97, "2026-11-07T03:10:00Z", "2026-11-07T08:00:00Z") + line("2026-11-06T22:00:00Z", "JUL", 1, 1, "2026-11-07T03:00:00Z", "2026-11-09T22:00:00Z"));
        Files.writeString(dir.resolve("2026-11-07.jsonl"), line("2026-11-07T06:36:59.369477500Z", "NSA", 10, 99, "2026-11-07T08:10:00Z", "2026-11-07T08:00:00Z"));
        var r = LastReadings.load(dir, 3);
        assertEquals(Instant.parse("2026-11-07T06:36:59.369477500Z"), r.get("NSA").t());
        assertEquals(99.0, r.get("NSA").u7d());
        assertEquals(Instant.parse("2026-11-07T08:00:00Z"), r.get("NSA").reset7d().toInstant());
        assertEquals(Instant.parse("2026-11-06T22:00:00Z"), r.get("JUL").t(), "an account with no line today keeps yesterday's");
    }

    @Test void aMissingFolderOrABrokenLineGivesWhatCanBeRead(@TempDir Path dir) throws Exception {
        assertTrue(LastReadings.load(dir.resolve("none"), 3).isEmpty());
        Files.writeString(dir.resolve("2026-11-07.jsonl"), "not json\n" + line("2026-11-07T06:00:00Z", "A", 1, 2, "2026-11-07T09:00:00Z", "2026-11-09T09:00:00Z"));
        assertEquals(1, LastReadings.load(dir, 3).size());
    }

    @Test void thePageKeepsTheResetsWhileTheyAreAheadAndTheReadingsAge() {
        var now = Instant.parse("2026-11-07T07:55:00Z");
        var r = LastReadings.load(Path.of("none"), 1);
        var reading = new LastReadings.Reading("NSA", Instant.parse("2026-11-07T06:36:59Z"), 10.0, 99.0, java.time.OffsetDateTime.parse("2026-11-07T08:10:00Z"), java.time.OffsetDateTime.parse("2026-11-07T08:00:00Z"));
        var page = LastReadings.page(new CCAPICredential().id("NSA").tier(5), reading, now);
        assertEquals(99.0, page.usageData().sevenDay().utilization());
        assertEquals(java.time.OffsetDateTime.parse("2026-11-07T08:00:00Z"), page.usageData().sevenDay().resetsAt());
        assertEquals(Duration.ofMinutes(78).plusSeconds(1), Duration.between(page.fetchedAt().toInstant(), now), "the page says how old the reading is");
    }

    @Test void aWindowThatHasResetSinceTheReadingIsBackAtZero() {
        var now = Instant.parse("2026-11-07T09:00:00Z");
        var reading = new LastReadings.Reading("NSA", Instant.parse("2026-11-07T06:36:59Z"), 10.0, 99.0, java.time.OffsetDateTime.parse("2026-11-07T08:10:00Z"), java.time.OffsetDateTime.parse("2026-11-07T08:00:00Z"));
        var page = LastReadings.page(new CCAPICredential().id("NSA").tier(5), reading, now);
        assertEquals(0.0, page.usageData().sevenDay().utilization(), "its week has reset: nothing used since");
        assertNull(page.usageData().sevenDay().resetsAt());
        assertEquals(0.0, page.usageData().fiveHour().utilization());
    }
}
