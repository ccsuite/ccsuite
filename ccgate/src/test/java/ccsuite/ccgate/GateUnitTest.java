package ccsuite.ccgate;

import module java.base;
import ccsuite.quotaweights.QuotaWeights;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GateUnitTest {

    final QuotaWeights weights = QuotaWeights.standard();

    @Test
    void measuredCallsCostOnePointPerMeasuredInterval() {
        assertEquals(1.0, new Usage().inputTokens(116_415).cost(weights, "claude-sonnet-5-5").points(), 0.01);
        assertEquals(1.0, new Usage().outputTokens(24_995).cost(weights, "claude-sonnet-5-5").points(), 0.01);
        assertEquals(1.0, new Usage().inputTokens(1_876_324).cost(weights, "claude-haiku-5-5").points(), 0.02);
        assertEquals(1.0, new Usage().inputTokens(89_924).cost(weights, "claude-opus-5-5").points(), 0.01);
        assertEquals(1.0, new Usage().cacheReadInputTokens(4_615_260).cost(weights, "claude-sonnet-5-5").points(), 0.01);
        assertEquals("haiku", weights.family("claude-haiku-4-5-20251001"));
        assertEquals("unknown", weights.family("some-new-model"));
        assertTrue(new Usage().inputTokens(1000).cost(weights, "some-new-model").points() > 0);
    }

    @Test
    void aCacheWriteNotSplitCountsAsFiveMinutes() {
        var unsplit = new Usage().cacheCreationInputTokens(1000).cost(weights, "claude-sonnet-5-5");
        var split = new Usage().cacheCreation5m(1000).cost(weights, "claude-sonnet-5-5");
        var asInput = new Usage().inputTokens(1250).cost(weights, "claude-sonnet-5-5");
        assertEquals(split.points(), unsplit.points(), 1e-12);
        assertEquals(asInput.points(), unsplit.points(), 1e-9, "a 5-minute write weighs 1.25 times input");
        assertTrue(new Usage().cacheCreation1h(1000).cost(weights, "claude-sonnet-5-5").points() > unsplit.points());
    }

    @Test
    void readsUsageFromAStreamWithoutKeepingIt() throws IOException {
        var sse = String.join("\n",
                "event: message_start",
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"claude-sonnet-5-5\",\"usage\":{\"input_tokens\":12,\"cache_creation_input_tokens\":100,\"cache_read_input_tokens\":2000,\"cache_creation\":{\"ephemeral_5m_input_tokens\":100,\"ephemeral_1h_input_tokens\":0},\"output_tokens\":1}}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}",
                "",
                "event: message_delta",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":42}}",
                "") + "\n";
        var out = new ByteArrayOutputStream();
        var a = UsageTee.pump(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), out, true);
        assertEquals(sse, out.toString(StandardCharsets.UTF_8));
        assertEquals("msg_1", a.messageId());
        assertEquals("end_turn", a.stopReason());
        assertEquals(12, a.usage().inputTokens());
        assertEquals(42, a.usage().outputTokens());
        assertEquals(2000, a.usage().cacheReadInputTokens());
        assertEquals(100, a.usage().cacheCreation5m());
    }

    @Test
    void identityLineAddedOnlyWhenMissing() throws IOException {
        var bare = BodyRewrite.parse("{\"model\":\"m\",\"system\":\"You classify.\",\"messages\":[]}".getBytes());
        BodyRewrite.prepare(bare, null);
        assertEquals(BodyRewrite.CLAUDE_CODE, bare.path("system").get(0).path("text").asText());
        assertEquals("You classify.", bare.path("system").get(1).path("text").asText());

        var cc = BodyRewrite.parse(("{\"system\":[{\"type\":\"text\",\"text\":\"x-anthropic-billing-header: cc_version=1;\"},"
                + "{\"type\":\"text\",\"text\":\"" + BodyRewrite.AGENT_SDK + "\"}],\"messages\":[]}").getBytes());
        BodyRewrite.prepare(cc, new Grant().id("g").purpose("reel editing").models(List.of("haiku")));
        assertEquals(3, cc.path("system").size());
        assertTrue(cc.path("system").get(2).path("text").asText().contains("reel editing"));
    }

    @Test
    void accountIdIsRewrittenInMetadata() throws IOException {
        var req = BodyRewrite.parse("{\"metadata\":{\"user_id\":\"{\\\"device_id\\\":\\\"d\\\",\\\"account_uuid\\\":\\\"old\\\",\\\"session_id\\\":\\\"s\\\"}\"}}".getBytes());
        var out = new String(BodyRewrite.forAccount(req, "new-acct"), StandardCharsets.UTF_8);
        assertTrue(out.contains("new-acct") && !out.contains("\\\"old\\\""), out);
    }

    @Test
    void budgetsAndModelsAreEnforced() {
        var spend = new Spend();
        var policy = new Policy(spend);
        var key = new KeyRef().fingerprint("fp");
        var g = new Grant().id("video").models(List.of("haiku", "sonnet")).budget(new LinkedHashMap<>(Map.of("sonnet", 2.0)))
                .expires(Instant.now().plusSeconds(3600).toString()).keys(List.of(key));
        var call = new Call().key("k").keyFingerprint("fp").grant(g).keyRef(key).family("sonnet");
        assertTrue(policy.check(call, Instant.now()).isEmpty());
        spend.add(new LedgerEntry().grant("video").family("sonnet").points(2.5).t(Instant.now().toString()));
        assertEquals(429, policy.check(call, Instant.now()).orElseThrow().status());
        assertEquals(403, policy.check(call.family("opus"), Instant.now()).orElseThrow().status());
        assertEquals(401, policy.check(new Call().key("k"), Instant.now()).orElseThrow().status());
        assertTrue(policy.check(call.family("haiku"), Instant.now()).isEmpty());
    }
}
