package ccsuite.ccacc;

import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** R2, R3 (PRP 78): the estimator's room per account, its correction by the gauge, the prediction and its error, and the placement of a new conversation. */
class FreeEstimatorTest {

    static final Instant T0 = Instant.parse("2026-11-07T10:00:00Z");
    static final long RESET = T0.getEpochSecond() + 3 * 3600;

    FreeEstimator est() { return new FreeEstimator(new LaneSettings()); }

    @Test void aReadingThatDiffersFromTheEstimateMovesTheEstimateToTheReadingAndTheCorrectionIsReported() {
        var e = est();
        assertTrue(e.observe("A", T0, 0.40, 0.05, RESET, "probe").isPresent(), "the first reading of an account is kept");
        e.spent("A", T0.plusSeconds(60), 14);
        assertEquals(46, e.room("A", T0.plusSeconds(60)).now(), 1e-9, "40 read plus 14 predicted: 54 used");
        var correction = e.observe("A", T0.plusSeconds(120), 0.58, 0.05, RESET, "probe").orElseThrow();
        assertEquals(54, correction.estimate5(), 1e-9);
        assertEquals(58, correction.reading5(), 1e-9);
        assertEquals(4, correction.correction5(), 1e-9, "the call cost 4 points more than predicted: the size of the correction");
        assertEquals(42, e.room("A", T0.plusSeconds(120)).now(), 1e-9, "the estimate is now the reading");
        assertTrue(e.observe("A", T0.plusSeconds(180), 0.58, 0.05, RESET, "probe").isEmpty(), "a reading that agrees needs no correction");
    }

    @Test void anOlderReadingNeverOverwritesANewerOne() {
        var e = est();
        e.observe("A", T0.plusSeconds(100), 0.50, null, RESET, "probe");
        e.observe("A", T0, 0.10, null, RESET, "probe");
        assertEquals(50, e.room("A", T0.plusSeconds(101)).now(), 1e-9);
    }

    @Test void aWindowThatHasResetCountsAsEmptyAndAnAccountNeverReadAsUnknownRoom() {
        var e = est();
        e.observe("A", T0, 0.90, 0.1, RESET, "probe");
        assertEquals(10, e.room("A", T0.plusSeconds(3600)).now(), 1e-9);
        var after = e.room("A", Instant.ofEpochSecond(RESET + 60));
        assertEquals(100, after.now(), 1e-9);
        assertNull(after.resetsAt(), "the next window starts with the next call");
        assertEquals(100, e.room("never-read", T0).predicted(), 1e-9);
        assertFalse(e.room("never-read", T0).known());
    }

    @Test void theCostOfACallInFlightIsHeldBackFromThePredictedRoomUntilItEnds() {
        var e = est();
        e.observe("A", T0, 0.20, 0.0, RESET, "probe");
        var token = e.begin("A", 15);
        var r = e.room("A", T0);
        assertEquals(80, r.now(), 1e-9);
        assertEquals(65, r.predicted(), 1e-9);
        assertEquals(1, r.inFlight());
        e.release(token);
        assertEquals(80, e.room("A", T0).predicted(), 1e-9);
    }

    @Test void theCostIsPredictedFromSizeAndCacheStateAndLearnedFromTheCallsGaugeMovement() {
        var e = est();
        assertEquals(0.3 + 0.6 * 7 + 0.7, e.predict(70_000, "new"), 1e-9, "a new chat: its start, 0.6 points per 10,000 characters of prompt, the expected reply");
        assertEquals(0.6 * 7 + 0.7, e.predict(70_000, "continued"), 1e-9, "a continued chat has no start to pay");
        assertEquals(0.3 * (0.3 + 0.6 * 7 + 0.7), e.predict(70_000, "new", "claude-haiku-5-5"), 1e-9, "Haiku costs about a third of Sonnet");
        e.observe("A", T0, 0.40, 0.02, RESET, "probe");
        // a lone call of 70,000 characters moved the gauge by 14 points while 15.4 were predicted
        var c = FreeLedgerTest.call(T0.plusSeconds(30), "A", 70_000, 0.40, 0.54);
        var token = e.begin("A", 15.4);
        e.observe("A", T0.plusSeconds(29), 0.54, 0.03, RESET, "call");
        e.finish(token, new FreeLedger.Call(c.t(), "A", c.model(), 70_000, 17_500, "new", 200, 1000, 0.40, 0.02, RESET, 0.54, 0.03, RESET, 15.4, 14.0, true, "s"));
        var r = e.room("A", T0.plusSeconds(31));
        assertEquals(15.4 - 14.0, r.lastError(), 1e-9, "predicted minus observed is kept: the error of the last prediction");
        assertEquals(1, r.errors());
        assertEquals(46, r.now(), 1e-9, "the call's own reading was applied once, the prediction is not added on top");
        assertEquals(0.6 + 0.25 * (13.0 / 7 - 0.6), e.rate("claude-sonnet-5-5"), 1e-9, "14 observed minus the start (0.3) and the reply (0.7) leaves 13 points for 7 units: the rate moved a quarter of the way to 1.857");
        assertEquals(0.6 * 0.3, e.rate("claude-haiku-5-5"), 1e-9, "Haiku has its own rate");
    }

    @Test void aCallThatOverlappedAnotherTeachesNothingBecauseItsGaugeMovementIsNotItsOwn() {
        var e = est();
        e.observe("A", T0, 0.0, 0.0, RESET, "probe");
        var first = e.begin("A", 10);
        var second = e.begin("A", 10);
        assertFalse(first.alone());
        assertFalse(second.alone());
        e.finish(first, new FreeLedger.Call(T0, "A", "m", 100_000, 25_000, "new", 200, 1, 0.0, 0.0, RESET, 0.30, 0.0, RESET, 22.0, 30.0, first.alone(), "s"));
        assertEquals(0.6, e.rate("claude-sonnet-5-5"), 1e-9);
        assertEquals(0, e.room("A", T0).errors());
    }

    @Test void aCallWithNoReadingIsAddedToTheWindowByItsPrediction() {
        var e = est();
        e.observe("A", T0, 0.10, 0.0, RESET, "probe");
        var token = e.begin("A", 8);
        e.finish(token, FreeLedgerTest.call(T0.plusSeconds(10), "A", 40_000, null, null));   // predicted 15.4 in the helper
        assertEquals(100 - 10 - 15.4, e.room("A", T0.plusSeconds(11)).now(), 1e-9);
        var failed = e.begin("A", 8);
        e.finish(failed, new FreeLedger.Call(T0.plusSeconds(20), "A", "m", 40_000, 10_000, "new", 429, 1, null, null, null, null, null, null, 8, null, true, "s"));
        assertEquals(100 - 10 - 15.4, e.room("A", T0.plusSeconds(21)).now(), 1e-9, "a refused call costs nothing");
    }

    // ---- replay: the room after a restart

    @Test void theStateRebuiltFromTheLedgerIsTheStateThatWasLeft(@TempDir Path dir) {
        var ledger = new FreeLedger(dir);
        var live = est();
        var now = T0.plusSeconds(900);
        // what the lane does for each call: begin, the call's reading, finish, and the ledger lines
        live.observe("A", T0, 0.20, 0.02, RESET, "probe").ifPresent(ledger::append);
        live.observe("B", T0, 0.70, 0.10, RESET, "probe").ifPresent(ledger::append);
        var t1 = live.begin("A", 15.4);
        live.observe("A", T0.plusSeconds(60), 0.33, 0.03, RESET, "call").ifPresent(ledger::append);
        var c1 = new FreeLedger.Call(T0.plusSeconds(61), "A", "m", 70_000, 17_500, "new", 200, 1, 0.20, 0.02, RESET, 0.33, 0.03, RESET, 15.4, 13.0, t1.alone(), "s1");
        ledger.append(c1);
        live.finish(t1, c1);
        var t2 = live.begin("B", 6.6);   // no reading came with this one
        var c2 = new FreeLedger.Call(T0.plusSeconds(120), "B", "m", 30_000, 7_500, "new", 200, 1, 0.70, 0.10, RESET, null, null, null, 6.6, null, t2.alone(), "s2");
        ledger.append(c2);
        live.finish(t2, c2);
        live.observe("B", T0.plusSeconds(300), 0.80, 0.10, RESET, "probe").ifPresent(ledger::append);   // a probe read more than predicted

        var back = FreeEstimator.fromLedger(new FreeLedger(dir), new LaneSettings(), now);
        for (var id : List.of("A", "B")) {
            var l = live.room(id, now);
            var r = back.room(id, now);
            assertEquals(l.now(), r.now(), 1e-9, id + " room now");
            assertEquals(l.usedWeek(), r.usedWeek(), 1e-9, id + " week");
            assertEquals(l.resetsAt(), r.resetsAt(), id + " reset");
            assertEquals(l.errors(), r.errors(), id + " prediction errors kept");
        }
        assertEquals(live.rate("claude-sonnet-5-5"), back.rate("claude-sonnet-5-5"), 1e-9, "the learned rate comes back too");
        assertEquals(67.0, back.room("A", now).now(), 1e-9);
        assertEquals(20.0, back.room("B", now).now(), 1e-9, "the later probe reading wins over the predicted spend");
    }

    // ---- allocation

    static FreeEstimator withRooms(Map<String, Double> usedFive, Map<String, Long> resets) {
        var e = new FreeEstimator(new LaneSettings());
        usedFive.forEach((id, used) -> e.observe(id, T0, used / 100, 0.01, resets.getOrDefault(id, RESET), "probe"));
        return e;
    }

    @Test void aNewConversationGoesToTheAccountWithTheMostPredictedRoom() {
        var e = withRooms(Map.of("A", 70.0, "B", 40.0, "C", 55.0), Map.of());
        var p = e.place(List.of("A", "B", "C"), 15.4, T0);
        assertEquals("B", p.account());
        assertEquals(60, p.room(), 1e-9);
    }

    @Test void anAccountWhoseWindowResetsSoonIsWorthMoreThanItsReadingSays() {
        var e = withRooms(Map.of("A", 70.0, "B", 60.0), Map.of("A", T0.getEpochSecond() + 600));   // A has 30 left but refills in 10 minutes, B has 40 left and 3 hours to go
        assertEquals("A", e.place(List.of("A", "B"), 15.4, T0).account());
    }

    @Test void theMarginAfterTheCallDecidesWhoFitsAndOnlyWhenNoneFitsIsThereNoPlacement() {
        var e = withRooms(Map.of("A", 85.0, "B", 90.0), Map.of());
        assertNull(e.place(List.of("A", "B"), 15.4, T0), "15 left and 10 left: neither survives the call with the margin of 5");
        assertEquals("A", e.place(List.of("A", "B"), 9.0, T0).account(), "a smaller call fits A (15 - 9 = 6 of margin)");
        assertNotNull(e.place(List.of("A", "B", "fresh"), 15.4, T0), "an account never read counts as empty");
    }

    @Test void aWeekThatIsUsedUpExcludesTheAccountWhateverItsFiveHourRoom() {
        var e = new FreeEstimator(new LaneSettings());
        e.observe("A", T0, 0.0, 0.99, RESET, "probe");
        e.observe("B", T0, 0.50, 0.10, RESET, "probe");
        assertEquals("B", e.place(List.of("A", "B"), 15.4, T0).account());
    }

    @Test void twoConversationsStartedTogetherDoNotBothGoToTheSameAccount() {
        var e = withRooms(Map.of("A", 40.0, "B", 45.0), Map.of());
        var first = e.place(List.of("A", "B"), 15.4, T0);
        assertEquals("A", first.account());
        e.begin(first.account(), first.need());   // the first one is on its way
        assertEquals("B", e.place(List.of("A", "B"), 15.4, T0).account(), "A is held back by the first call (44.6 left), so B has more");
    }

    // ---- PRP 78: what the free lane can carry at all

    @Test void theZonesAreTheSafeStartAndTheWorkZoneOfTheMeasuredWindow() {
        var e = est();
        assertEquals(680_000, e.windowInputTokens(), "a completely unloaded account takes about 680,000 input tokens per window (measured 660,000 to 770,000)");
        assertEquals(340_000, e.actualMaxInputTokens(), "the safe start: half the window, the rest kept to summarise");
        assertEquals(170_000, e.workZoneTokens(), "work freely up to here, then close the cycle; Claude Code compacts here");
        var ceiling = e.ceilingChars();
        assertEquals((100 - 5 - 0.3 - 0.7) / 0.6 * 10_000, ceiling, 1e-6, "100 points, less the margin, the start and the reply, at 0.6 points per 10,000 characters: no summary reserve on the points (PRP 84)");
        assertEquals((long) Math.ceil(ceiling / ccsuite.webproxy.TokenEstimate.CHARS_PER_TOKEN), e.claudeCodeTokens(ceiling), "characters over the proxy's one ratio");
        assertTrue(e.predictStart(Math.round(ceiling), "claude-sonnet-5-5") <= 95.0001, "a request of exactly the ceiling is predicted to leave the margin");
        var resumed = e.predictStart(655_260, "claude-sonnet-5-5");
        assertTrue(resumed > 30 && resumed < 50, "the 655,260 characters of the resumed conversation cost " + resumed + " points: the start, the prompt at the measured rate and the reply, no summary reserve");
        assertTrue(e.predictStart(1_800_000, "claude-sonnet-5-5") > 95 - 1e-9 || e.claudeCodeTokens(1_800_000) > e.actualMaxInputTokens(), "1,800,000 characters are beyond the safe start");
        assertTrue(e.maxChars(100, "claude-haiku-5-5") > 2 * ceiling, "Haiku takes much more");
    }

    @Test void whatFitsShrinksWithTheLoadOfTheAccount() {
        var e = est();
        assertTrue(e.maxChars(100, EST_SONNET) > e.maxChars(60, EST_SONNET) && e.maxChars(60, EST_SONNET) > e.maxChars(20, EST_SONNET));
        assertEquals((60 - 5 - 0.3 - 0.7) / 0.6 * 10_000, e.maxChars(60, EST_SONNET), 1e-6, "60 points left: the biggest uncached prompt the points take");
        assertEquals(0, e.maxChars(5, EST_SONNET), 1e-9, "too little left to start a chat at all");
        var lines = String.join(" ", e.limitLines());
        assertTrue(lines.contains("contextLimitTokens=874453") && lines.contains("windowInputTokens=680000") && lines.contains("actualMaxInputTokens=340000") && lines.contains("workZoneTokens=170000") && lines.contains("compactTokens=425000") && lines.contains("haikuCeilingChars="), lines);
    }

    @Test void theClientCompactionWindowPutsItsOwnFirePointAboveTheHardZone() {
        var e = est();
        assertTrue(e.compactWindowTokens() * FreeEstimator.COMPACT_FIRE_FRACTION >= e.actualMaxInputTokens(), "Claude Code compacts at about 80% of the window (PRP 86, inferred): that point must not lie below hard");
        assertTrue(e.compactWindowTokens() * FreeEstimator.COMPACT_FIRE_FRACTION - e.actualMaxInputTokens() < 1.0 / FreeEstimator.COMPACT_FIRE_FRACTION, "and not higher than a rounding step above it");
        assertEquals(170_000, e.workZoneTokens(), "the work zone itself is unchanged: only the client's backstop moved");
        assertEquals(425_000, e.compactWindowTokens());
    }

    @Test void theLedgerLearnsHowMuchPromptTheProxyMakesOfABodyAndFitsGrowWithIt() {
        var e = est();
        e.observe("A", T0, 0.1, 0.0, RESET, "probe");
        var before = e.ceilingChars();
        e.finish(e.begin("A", 20), new FreeLedger.Call(T0.plusSeconds(5), "A", "claude-sonnet-5-5", 100_000, 25_000, "new", 200, 1, null, null, null, null, null, null, 20, null, true, "s", 80_000L));
        assertEquals(1.0 + 0.25 * (0.8 - 1.0), e.promptPerBody(), 1e-9, "the prompt was 80% of the body");
        assertTrue(e.ceilingChars() > before, "a body is cheaper than it looked: more fits");
    }

    static final String EST_SONNET = "claude-sonnet-5-5";

    @Test void theBestRoomAndTheNextResetAreReported() {
        var e = withRooms(Map.of("A", 70.0, "B", 40.0), Map.of("A", T0.getEpochSecond() + 600));
        assertEquals(60, e.bestRoom(List.of("A", "B"), T0), 1e-9);
        assertEquals(Instant.ofEpochSecond(T0.getEpochSecond() + 600), e.nextReset(List.of("A", "B"), T0).orElseThrow().at());
        assertEquals("A", e.nextReset(List.of("A", "B"), T0).orElseThrow().account(), "and on which account");
        assertEquals(-1, e.bestRoom(List.of(), T0));
    }
}
