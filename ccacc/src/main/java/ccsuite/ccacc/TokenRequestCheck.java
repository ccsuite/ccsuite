package ccsuite.ccacc;

import module java.base;
import module java.net.http;
import ccsuite.webproxy.RequestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@code ccacc tokencheck model request-check} (PRP 85): the estimate of {@link RequestTokens} against the paid count_tokens API for COMPLETE requests: whole sessions
 * as the messages, with several system prompts and several sets of tool definitions, which the content model was never fitted on. It shows what the framing constants
 * of {@code token-weights.json} should be (the difference between the real count and the content estimate, per tool definition) and how accurate the total is.
 * Free: count_tokens only.
 */
final class TokenRequestCheck {
    static int run(Path dir, Path root, int port, String model, int sessions) throws IOException {
        var http = HttpClient.newHttpClient();
        var tools = new ArrayList<JsonNode>();
        try (var in = TokenRequestCheck.class.getResourceAsStream("/ccsuite/ccacc/sample-tools.json")) { TokenCheckTexts.JSON.readTree(in).forEach(tools::add); }
        var prose = TokenCheckTexts.all(Path.of("").toAbsolutePath(), List.of()).stream().filter(t -> t.kind().equals("prose")).map(t -> t.messages().get(0).path("content").asText()).collect(Collectors.joining("\n\n"));
        var long1 = TokenCheckTexts.JSON.createArrayNode().add(TokenCheckTexts.JSON.createObjectNode().put("type", "text").put("text", "You are Claude Code, Anthropic's official CLI for Claude.")).add(TokenCheckTexts.JSON.createObjectNode().put("type", "text").put("text", prose.substring(0, Math.min(prose.length(), 14_000))));
        var short1 = TokenCheckTexts.JSON.createArrayNode().add(TokenCheckTexts.JSON.createObjectNode().put("type", "text").put("text", "You are Claude Code, Anthropic's official CLI for Claude."));
        var variants = new LinkedHashMap<String, Object[]>();
        variants.put("short system, no tools", new Object[]{short1, List.<JsonNode>of()});
        variants.put("long system, no tools", new Object[]{long1, List.<JsonNode>of()});
        for (var k : new int[]{1, 3, 6, tools.size()}) variants.put("short system, " + k + " tool definitions", new Object[]{short1, tools.subList(0, k)});
        variants.put("long system, all tool definitions", new Object[]{long1, tools});
        var picked = new ArrayList<JsonNode>();
        Files.readAllLines(dir.resolve("samples.jsonl")).stream().map(l -> { try { return TokenCheckTexts.JSON.readTree(l); } catch (IOException e) { return null; } })
            .filter(j -> j != null && j.path("set").asText().equals("whole") && j.path("net").asInt() < 60_000 && Files.exists(root.resolve(j.path("file").asText()))).limit(sessions).forEach(picked::add);
        var rows = new ArrayList<double[]>();   // variant index, real, estimate without framing, tool defs
        var names = new ArrayList<>(variants.keySet());
        for (var j : picked) {
            var messages = TokenCheckTexts.cleaned(root.resolve(j.path("file").asText()), TokenModelCli.MAX_CHARS);
            var arr = TokenModelCli.array(messages, new int[]{j.path("start").asInt(), j.path("end").asInt()}, true);
            for (var v = 0; v < names.size(); v++) {
                var system = (JsonNode) variants.get(names.get(v))[0];
                @SuppressWarnings("unchecked") var t = (List<JsonNode>) variants.get(names.get(v))[1];
                var body = TokenCheckTexts.JSON.createObjectNode().put("model", model);
                body.set("system", system);
                if (!t.isEmpty()) body.set("tools", TokenCheckTexts.JSON.createArrayNode().addAll(t));
                body.set("messages", arr);
                try { rows.add(new double[]{v, count(http, port, body), RequestTokens.of(arr, system, t, 0), t.size()}); Thread.sleep(120); }
                catch (IOException | InterruptedException e) { System.err.println(names.get(v) + ": " + e.getMessage()); }
            }
        }
        System.out.println("complete requests: %d sessions x %d variants, real counts from count_tokens against the estimate (framing constants as in token-weights.json now):".formatted(picked.size(), names.size()));
        for (var v = 0; v < names.size(); v++) {
            var vv = v;
            var r = rows.stream().filter(x -> x[0] == vv).toList();
            if (r.isEmpty()) continue;
            var score = TokenModel.Score.of(names.get(v), r.stream().mapToDouble(x -> x[2]).toArray(), r.stream().mapToDouble(x -> x[1]).toArray());
            System.out.println("  " + score.line() + "   mean real - estimate %+.0f tokens".formatted(r.stream().mapToDouble(x -> x[1] - x[2]).average().orElse(0)));
        }
        // the framing: what is left over once the content is estimated, by number of tool definitions (a straight line: fixed + per definition)
        var byDefs = new TreeMap<Double, Double>();
        rows.stream().collect(Collectors.groupingBy(x -> x[3])).forEach((k, v) -> byDefs.put(k, v.stream().mapToDouble(x -> x[1] - x[2]).average().orElse(0)));
        System.out.println("  leftover tokens by number of tool definitions (mean real - estimate): " + byDefs);
        return 0;
    }

    static int count(HttpClient http, int port, ObjectNode body) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/messages/count_tokens")).timeout(Duration.ofMinutes(3))
            .header("content-type", "application/json").header("anthropic-version", "2023-06-01").header("authorization", "Bearer ccacc-local").header("x-ccacc-job", "tokencheck")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " " + response.body().replaceAll("\\s+", " ").substring(0, Math.min(160, response.body().length())));
        return TokenCheckTexts.JSON.readTree(response.body()).path("input_tokens").asInt();
    }

    private TokenRequestCheck() { }
}
