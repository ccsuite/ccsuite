package ccsuite.ccacc;

import module java.base;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The durable half of the sample log (PRP 85): a sample is a pointer into a Claude Code session file, and Claude Code prunes sessions after about 30 days, so
 * each sample's features and real count are also kept here ({@code features.jsonl}: numbers only, no text). {@link TokenModelCli#load} reads the session file
 * while it exists (so a changed feature definition is recomputed) and this file once it is gone, so a review years later still has every count ever made.
 */
final class TokenSnapshot {
    static Path file(Path dir) { return dir.resolve("features.jsonl"); }

    static Map<String, TokenModelCli.Sample> read(Path dir) throws IOException {
        var out = new HashMap<String, TokenModelCli.Sample>();
        if (!Files.exists(file(dir))) return out;
        for (var l : Files.readAllLines(file(dir))) {
            var j = TokenCheckTexts.JSON.readTree(l);
            var f = new LinkedHashMap<String, Double>();
            j.path("f").fields().forEachRemaining(e -> f.put(e.getKey(), e.getValue().asDouble()));
            out.put(j.path("key").asText(), new TokenModelCli.Sample(j.path("file").asText(), j.path("month").asText(), j.path("set").asText("chunk"), j.path("net").asDouble(), f, j.path("promptChars").asDouble(), j.path("key").asText()));
        }
        return out;
    }

    /** Rewrites the snapshot from every sample that can still be computed, and keeps the earlier entries of sessions that are gone. */
    static void refresh(Path dir, Path root) throws IOException {
        var all = TokenModelCli.load(dir.resolve("samples.jsonl"), root);
        var lines = new ArrayList<String>();
        for (var s : all) {
            var o = TokenCheckTexts.JSON.createObjectNode().put("key", s.key()).put("file", s.file()).put("month", s.month()).put("set", s.set()).put("net", s.net()).put("promptChars", s.promptChars());
            var f = o.putObject("f");
            s.f().forEach(f::put);
            lines.add(o.toString());
        }
        var tmp = dir.resolve("features.jsonl.tmp");
        Files.write(tmp, lines);
        Files.move(tmp, file(dir), StandardCopyOption.REPLACE_EXISTING);
        System.out.println("snapshot: " + lines.size() + " samples with their features -> " + file(dir));
    }

    private TokenSnapshot() { }
}
