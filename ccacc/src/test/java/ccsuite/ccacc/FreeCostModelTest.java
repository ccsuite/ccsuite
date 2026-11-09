package ccsuite.ccacc;

import module java.base;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** PRP 88: the terms cost model (measured weights, a scale per family and a factor per chat learned from the gauge), the reply meter, the chat state of the lane, and the ledger fields. */
class FreeCostModelTest {

    static final Instant T0 = Instant.parse("2026-11-09T04:40:00Z");
    static final String S = "claude-sonnet-5-5", H = "claude-haiku-5-5";

    static FreeLedger.Call call(Instant t, String cache, long prompt, long held, Long gap, long reply, Double observed, String session) {
        return new FreeLedger.Call(t, "A", S, prompt, prompt / 4, cache, 200, 1000, 0.1, 0.0, 1L, 0.2, 0.0, 1L, 1.0, observed, true, session, prompt, reply, held, gap, null);
    }

    @Test void theMeasuredCallsOfTheNinthArePredictedFromTheSettings() {
        var m = new FreeCostModel(new LaneSettings());
        assertEquals(24, m.raw(new FreeCostModel.Input(S, "new", 400_000, 0, null, null), 2), 0.5, "a 400K new chat with a short reply: measured 24 points");
        assertEquals(0.55, m.raw(new FreeCostModel.Input(S, "continued", 264, 300_000, 4L, null), 2), 0.1, "a short continuation of a warm 300K chat: measured 10 points over 20 calls");
        assertEquals(3.1, m.raw(new FreeCostModel.Input(S, "new", 1_700, 0, null, null), 14_400), 0.2, "a 14.4K reply: measured 3 points each, five times");
        assertEquals(m.raw(new FreeCostModel.Input(S, "new", 200_000, 0, null, null), 2) * 0.3, m.raw(new FreeCostModel.Input(H, "new", 200_000, 0, null, null), 2), 1e-9, "Haiku is the Haiku factor of Sonnet");
    }

    @Test void aChatHeldPastTheWarmTimeIsPricedCold() {
        var s = new LaneSettings();
        var m = new FreeCostModel(s);
        var warm = m.raw(new FreeCostModel.Input(S, "continued", 264, 200_000, (long) s.termsWarmSeconds() - 1, null), 2);
        var cold = m.raw(new FreeCostModel.Input(S, "continued", 264, 200_000, (long) s.termsWarmSeconds() + 1, null), 2);
        assertTrue(cold > warm * 10, "the cold rewrite costs far more than the cached read: " + warm + " against " + cold);
    }

    @Test void theScaleFollowsTheGaugeOnlyOnceAWindowOfPointsHasBeenSeen() {
        var m = new FreeCostModel(new LaneSettings());
        var raw = m.raw(new FreeCostModel.Input(S, "new", 100_000, 0, null, null), 2);
        m.learn(call(T0, "new", 100_000, 0, null, 2, raw * 2, "c1"));
        assertEquals(1.0, m.scale(S), 1e-9, "one call of " + raw * 2 + " points is under the window: no change");
        for (var i = 1; i < 5; i++) m.learn(call(T0.plusSeconds(i), "new", 100_000, 0, null, 2, raw * 2, "c" + (i + 1)));
        assertTrue(m.scale(S) > 1.2, "a window where the gauge moved twice the model raises the scale: " + m.scale(S));
        assertEquals(1.0, m.scale(H), 1e-9, "the other family is not touched");
    }

    @Test void oneJumpTheModelCannotExplainDoesNotDragTheScale() {
        var m = new FreeCostModel(new LaneSettings());
        var raw = m.raw(new FreeCostModel.Input(S, "continued", 264, 300_000, 5L, null), 2);
        m.learn(call(T0, "continued", 264, 300_000, 5L, 2, 25.0, "c1"));
        for (var i = 1; i <= 40; i++) m.learn(call(T0.plusSeconds(i), "continued", 264, 300_000, 5L, 2, raw, "c" + i));
        assertTrue(m.scale(S) < 1.1, "a 25-point jump among calls that cost what the model says (" + raw + ") is clipped, not fitted: scale " + m.scale(S));
    }

    @Test void aChatsOwnFactorSettlesWithinTwoOrThreeCallsAndAppliesOnlyToThatChat() {
        var m = new FreeCostModel(new LaneSettings());
        m.learn(call(T0, "new", 200_000, 0, null, 2, 12.0, "c1"));
        for (var i = 1; i <= 3; i++) m.learn(call(T0.plusSeconds(10L * i), "continued", 264, 200_000, 10L, 2, 3.0, "c1"));
        var f = m.chatFactor("A|c1");
        assertTrue(f > 1.2, "continuations costing far more than predicted raise the chat's factor: " + f);
        var in = new FreeCostModel.Input(S, "continued", 264, 200_000, 10L, "A|c1");
        assertEquals(m.raw(in, 2) * m.scale(S) * f, m.predict(in), 1e-9, "the next prediction of that chat carries its factor");
        assertEquals(1.0, m.chatFactor("A|other"), 1e-9);
    }

    @Test void theExpectedReplyIsTheChatsOwnMean() {
        var m = new FreeCostModel(new LaneSettings());
        assertEquals(new LaneSettings().termsReplyChars(), m.expectedReply("A|c1"), 1e-9);
        m.learn(call(T0, "new", 1000, 0, null, 10_000, null, "c1"));
        m.learn(call(T0.plusSeconds(5), "continued", 264, 11_000, 5L, 20_000, null, "c1"));
        assertEquals(15_000, m.expectedReply("A|c1"), 1e-9);
    }

    @Test void theMeterCountsTextToolInputAndThinkingOfAStreamWhateverTheChunking() {
        var sse = "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"a\\\":1}\"}}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}\n\n"
                + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
        var b = sse.getBytes(StandardCharsets.UTF_8);
        var m = new ReplyMeter();
        for (var i = 0; i < b.length; i += 7) m.feed(b, i, Math.min(7, b.length - i));
        assertEquals(5 + 7 + 3, m.chars());
    }

    @Test void theMeterMeasuresAPlainAnswerWhole() {
        var b = "{\"content\":[{\"type\":\"text\",\"text\":\"OK there\"}]}".getBytes(StandardCharsets.UTF_8);
        var m = new ReplyMeter();
        m.feed(b, 0, b.length);
        assertEquals(8, m.chars());
    }

    @Test void theLaneChatHoldsPromptsAndRepliesAndTakesTheResentReplyOffTheNewPart() {
        var c = new LaneChats();
        assertNull(c.input("k", "A", "s", S, "continued", 5000, 1.0, T0), "a continued chat the lane does not know: the model cannot tell");
        c.served("k", "new", 100_000, 3_000, T0);
        var in = c.input("k", "A", "s", S, "continued", 8_000, 1.0, T0.plusSeconds(30));
        assertEquals(103_000, in.heldChars(), 1e-9);
        assertEquals(5_000, in.promptChars(), 1e-9, "8,000 new in the body, 3,000 of it the reply the web holds");
        assertEquals(30, in.gapS());
        c.served("k", "continued", 5_000, 1_000, T0.plusSeconds(30));
        assertEquals(109_000, c.get("k").held(), 1e-9);
        c.served("k", "new", 50_000, 0, T0.plusSeconds(60));
        assertEquals(50_000, c.get("k").held(), 1e-9, "a new chat starts again");
    }

    @Test void theLedgerKeepsThePrp88FieldsAndReadsOlderLinesWithout() throws Exception {
        var c = call(T0, "continued", 500, 9_000, 12L, 700, 1.0, "s").toMap();
        var back = FreeLedger.Call.of(FreeLedger.JSON.readTree(FreeLedger.JSON.writeValueAsString(c)));
        assertEquals(700L, back.replyChars());
        assertEquals(9_000L, back.heldChars());
        assertEquals(12L, back.gapS());
        var old = FreeLedger.Call.of(FreeLedger.JSON.readTree("{\"t\":\"2026-11-08T00:00:00Z\",\"account\":\"A\",\"chars\":5,\"cache\":\"new\",\"status\":200}"));
        assertNull(old.replyChars());
        assertNull(old.predictedOther());
    }

    @Test void theGaugeReportShowsTheOtherModelOnTheSameRuns() {
        var calls = new ArrayList<FreeLedger.Call>();
        for (var i = 0; i < 5; i++)
            calls.add(new FreeLedger.Call(T0.plusSeconds(60L * i), "A", S, 1000, 250, "continued", 200, 1000, 0.10 + 0.1 * i, 0.0, 9L, 0.20 + 0.1 * i, 0.0, 9L, 8.0, 10.0, true, "s", 1000L, 500L, 1000L, 5L, 10.0));
        var runs = GaugeCheck.runs(calls);
        assertEquals(1, runs.size());
        assertEquals(50, runs.getFirst().observed(), 1e-6);
        assertEquals(40, runs.getFirst().predicted(), 1e-9);
        assertEquals(50, runs.getFirst().shadow(), 1e-9);
        var text = String.join("\n", GaugeCheck.report(runs, 5, 40));
        assertTrue(text.contains("(other model 50.0, ") && text.contains("5 of 5 calls)"), text);
        assertTrue(text.contains("in use 20.0% mean deviation, 0 within 5%; the other 0.0%, 1 within"), text);
    }

    @Test void theEstimatorUsesTheRateModelUnlessTermsIsChosenAndKeepsTheOtherAsShadow() {
        var s = new LaneSettings();
        var e = new FreeEstimator(s);
        var in = new FreeCostModel.Input(S, "new", 400_000, 0, null, null);
        var rate = e.predictBoth(400_000, "new", S, in);
        assertEquals(e.predict(400_000, "new", S), rate[0], 1e-9);
        assertEquals(e.terms.predict(in), rate[1], 1e-9);
        s.costModel("terms");
        var terms = e.predictBoth(400_000, "new", S, in);
        assertEquals(rate[1], terms[0], 1e-9);
        assertEquals(rate[0], terms[1], 1e-9);
        assertArrayEquals(new double[]{e.predict(10, "continued", S), e.predict(10, "continued", S)}, e.predictBoth(10, "continued", S, null), 1e-9, "no input: the rate model for both");
    }
}
