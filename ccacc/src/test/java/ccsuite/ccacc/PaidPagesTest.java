package ccsuite.ccacc;

import ccsuite.base.model.CCAPICredential;
import ccsuite.oauthclient.AccountUsage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The paid views never show a free account, whatever flags it carries. */
class PaidPagesTest {

    @Test void aFreeAccountIsLeftOutEvenWithTrackUsageOn() {
        var soon = Instant.now().plusSeconds(3600).toString();
        var u = new LinkedHashMap<String, AccountUsage>();
        u.put("ann", new AccountUsage(40.0, soon, 50.0, soon, 50.0));
        u.put("FREE", new AccountUsage(5.0, soon, 5.0, null, null));
        u.put("dan", new AccountUsage(10.0, soon, 20.0, soon, 50.0));
        var creds = List.of(new CCAPICredential().id("ann").tier(5), new CCAPICredential().id("FREE").tier(0).trackUsage(true), new CCAPICredential().id("dan").tier(1));
        var pages = PaidPages.of(u, creds);
        assertEquals(List.of("ann", "dan"), pages.stream().map(p -> p.credential().id()).toList());
        assertNotNull(PaidPages.all(pages), "ALL is made of the paid accounts only");
        assertEquals(2, PaidPages.all(pages).credential().tier(), "and counts them: two");
    }

    @Test void anAccountTheStoreDoesNotKnowYetCountsAsPaid() {
        var u = new LinkedHashMap<String, AccountUsage>();
        u.put("new", new AccountUsage(5.0, null, null, null, null));
        assertEquals(1, PaidPages.of(u, List.of()).size());
    }

    static ccsuite.usagetracker.model.LastReadings.Reading line(String id, String t) {
        return new ccsuite.usagetracker.model.LastReadings.Reading(id, Instant.parse(t), 10.0, 99.0, java.time.OffsetDateTime.parse("2026-11-07T08:10:00Z"), java.time.OffsetDateTime.parse("2026-11-07T08:00:00Z"));
    }

    @Test void anAccountTheWatcherHasNoReadingForShowsItsLastLoggedReadingWithItsAge() {
        var now = Instant.parse("2026-11-07T07:55:00Z");
        var polled = new LinkedHashMap<String, AccountUsage>();
        polled.put("JUL", new AccountUsage(16.0, "2026-11-07T08:00:00Z", 63.0, "2026-11-09T22:00:00Z", 40.0));
        var creds = List.of(new CCAPICredential().id("JUL").tier(5).trackUsage(true), new CCAPICredential().id("NSA").tier(5).trackUsage(true),
            new CCAPICredential().id("OFF").tier(5).trackUsage(false), new CCAPICredential().id("FREE").tier(0).trackUsage(true));
        var logged = java.util.Map.of("NSA", line("NSA", "2026-11-07T06:36:59Z"), "OFF", line("OFF", "2026-11-07T06:36:59Z"), "FREE", line("FREE", "2026-11-07T06:36:59Z"), "JUL", line("JUL", "2026-11-07T06:00:00Z"));
        var pages = PaidPages.of(polled, creds, logged, now);
        assertEquals(List.of("JUL", "NSA"), pages.stream().map(p -> p.credential().id()).toList(), "a live reading wins; untracked and free accounts are not added from the log");
        var nsk = pages.get(1);
        assertEquals(99.0, nsk.usageData().sevenDay().utilization());
        assertEquals(java.time.Duration.ofMinutes(78).plusSeconds(1), java.time.Duration.between(nsk.fetchedAt().toInstant(), now));
    }
}
