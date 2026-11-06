package ccsuite.ccacc;

import module java.base;
import module java.net.http;
import ccsuite.localserver.server.AccountRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * PRP 75: does the prompt cache survive a switch of paid account? Skipped unless {@code -Dcache.experiment=<results .jsonl>} is given. One run is one sequence:
 * {@code -Dcache.model=<model id>} and {@code -Dcache.accounts=P1,P1,P2,P4,P1}. Each call sends the same ~6,000-token static prefix (a fresh nonce per run, so no
 * older cache can match) marked cache_control, plus a one-word question, over OAuth to the Messages API, and appends one JSON line: account, the organisation the
 * response says it served (anthropic-organization-id), the usage block (input, cache write, cache read, output) and the unified rate-limit utilisation headers.
 */
class CacheAcrossAccountsExperiment {

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    static String prefix(String nonce) {
        var sb = new StringBuilder("Reference notes " + nonce + ".\n");
        for (var i = 0; i < 250; i++) sb.append("Note ").append(i).append(" of ").append(nonce).append(": the quick brown fox jumps over the lazy dog while counting ").append(i * 7).append(" small stones by the river.\n");
        return sb.toString();
    }

    static String body(String model, String nonce) throws Exception { return body(model, nonce, null); }

    /** {@code ttl} "1h" asks for the one-hour cache; null is the default lifetime. */
    static String body(String model, String nonce, String ttl) throws Exception {
        var root = JSON.createObjectNode().put("model", model).put("max_tokens", 5);
        var system = root.putArray("system");
        system.addObject().put("type", "text").put("text", "You are Claude Code, Anthropic's official CLI for Claude.");
        var big = system.addObject().put("type", "text").put("text", prefix(nonce));
        var cc = big.putObject("cache_control").put("type", "ephemeral");
        if (ttl != null) cc.put("ttl", ttl);
        root.putArray("messages").addObject().put("role", "user").put("content", "Reply with the single word: ok");
        return JSON.writeValueAsString(root);
    }

    @Test @EnabledIfSystemProperty(named = "cache.experiment", matches = ".+")
    void oneSequence() throws Exception {
        var results = Path.of(System.getProperty("cache.experiment"));
        var model = System.getProperty("cache.model", "claude-haiku-5-5");
        var accounts = List.of(System.getProperty("cache.accounts", "P1,P1").split(","));
        var nonce = Long.toHexString(new Random().nextLong());
        var core = new Core();
        var router = new AccountRouter(core.swap, core.store, core.live, core.usage, core.oauth);
        var payload = body(model, nonce);
        System.out.println("model " + model + ", nonce " + nonce + ", prefix " + prefix(nonce).length() + " chars, sequence " + accounts);
        for (var n = 0; n < accounts.size(); n++) {
            var id = accounts.get(n).trim();
            var line = JSON.createObjectNode().put("t", Instant.now().toString()).put("run", nonce).put("n", n + 1).put("model", model).put("account", id);
            var token = router.tokenFor(id);
            if (token.isEmpty()) { line.put("error", "no token for " + id); record(results, line); continue; }
            var t0 = System.nanoTime();
            var res = HTTP.send(HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages")).timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + token.get()).header("anthropic-version", "2023-06-01").header("anthropic-beta", "oauth-2025-04-20")
                    .header("Content-Type", "application/json").header("User-Agent", "claude-swap/1.0").POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
            line.put("ms", (System.nanoTime() - t0) / 1_000_000).put("status", res.statusCode());
            res.headers().firstValue("anthropic-organization-id").ifPresent(v -> line.put("org", v));
            res.headers().firstValue("anthropic-ratelimit-unified-5h-utilization").ifPresent(v -> line.put("h5", v));
            res.headers().firstValue("anthropic-ratelimit-unified-7d-utilization").ifPresent(v -> line.put("h7", v));
            var json = JSON.readTree(res.body());
            if (res.statusCode() == 200) {
                var u = json.path("usage");
                line.put("input", u.path("input_tokens").asInt()).put("cacheWrite", u.path("cache_creation_input_tokens").asInt()).put("cacheRead", u.path("cache_read_input_tokens").asInt()).put("output", u.path("output_tokens").asInt());
            } else line.put("error", res.body().substring(0, Math.min(300, res.body().length())));
            record(results, line);
            Thread.sleep(1500);
        }
    }

    /** One timed step: at {@code at} seconds from the start, {@code account} sends the prefix of cache {@code key} (the first use of a key writes it). */
    record Step(int at, String key, String account) {}

    /**
     * How long does a cache live, and does a hit renew it? Skipped unless {@code -Dcache.ttl=<results .jsonl>}. Four caches are written at the start (X1, X2, X5 with the default
     * lifetime, X3 and X4 asking for one hour), then read later by OTHER accounts at set times; a cache that is gone shows up as a new write. X5 is read every three minutes
     * to see whether a hit renews it. The last step is at 62 minutes (the one-hour cache); results are appended as they come.
     */
    @Test @EnabledIfSystemProperty(named = "cache.ttl", matches = ".+")
    void timeToLive() throws Exception {
        var results = Path.of(System.getProperty("cache.ttl"));
        var model = System.getProperty("cache.model", "claude-haiku-5-5");
        var core = new Core();
        var router = new AccountRouter(core.swap, core.store, core.live, core.usage, core.oauth);
        var run = Long.toHexString(new Random().nextLong());
        var ttl = Map.of("X1", "", "X2", "", "X5", "", "X3", "1h", "X4", "1h");
        var steps = List.of(new Step(0, "X1", "P1"), new Step(2, "X2", "P1"), new Step(4, "X5", "P1"), new Step(6, "X3", "P1"), new Step(8, "X4", "P1"),
                new Step(180, "X5", "P2"), new Step(270, "X1", "P2"), new Step(360, "X2", "P3"), new Step(366, "X5", "P2"), new Step(540, "X5", "P3"),
                new Step(720, "X3", "P2"), new Step(3720, "X4", "P2"), new Step(3726, "X3", "P3"));
        var seen = new HashSet<String>();
        var t0 = System.nanoTime();
        for (var s : steps) {
            var wait = s.at() * 1000L - (System.nanoTime() - t0) / 1_000_000;
            if (wait > 0) Thread.sleep(wait);
            var line = JSON.createObjectNode().put("t", Instant.now().toString()).put("run", run).put("at", s.at()).put("model", model).put("cache", s.key()).put("account", s.account())
                    .put("first", seen.add(s.key())).put("ttl", ttl.get(s.key()).isEmpty() ? "default" : ttl.get(s.key()));
            var token = router.tokenFor(s.account());
            if (token.isEmpty()) { line.put("error", "no token"); record(results, line); continue; }
            var req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages")).timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + token.get())
                    .header("anthropic-version", "2023-06-01").header("anthropic-beta", "oauth-2025-04-20,extended-cache-ttl-2025-04-11").header("Content-Type", "application/json").header("User-Agent", "claude-swap/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(body(model, run + s.key(), ttl.get(s.key()).isEmpty() ? null : ttl.get(s.key())))).build();
            var res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            line.put("status", res.statusCode());
            if (res.statusCode() == 200) {
                var u = JSON.readTree(res.body()).path("usage");
                line.put("cacheWrite", u.path("cache_creation_input_tokens").asInt()).put("cacheRead", u.path("cache_read_input_tokens").asInt());
            } else line.put("error", res.body().substring(0, Math.min(300, res.body().length())));
            record(results, line);
        }
    }

    static void record(Path file, com.fasterxml.jackson.databind.node.ObjectNode line) throws IOException {
        System.out.println(line);
        Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
