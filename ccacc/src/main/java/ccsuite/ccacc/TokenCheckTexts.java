package ccsuite.ccacc;

import module java.base;
import ccsuite.webproxy.MessageToPromptConverter;
import ccsuite.webproxy.TokenEstimate;
import ccsuite.webproxy.anthropic.AnthropicRequest;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * The fixed set of texts the free estimate is checked on (PRP 85, R4): Java source, JSON, prose and two conversation transcripts. Plain texts are one user
 * message and their size is their own characters; a transcript's size is the converted prompt the free lane would charge for (what its usage block is
 * computed from). The transcripts are cut from this project's own Claude Code sessions, with system reminders dropped and every id, address and long hex
 * string replaced by a fake one; nothing of them is stored.
 */
final class TokenCheckTexts {
    static final ObjectMapper JSON = new ObjectMapper();
    static final ObjectMapper LENIENT = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    static final int TRANSCRIPT_CHARS = 120_000, JSON_CHARS = 30_000;
    static final Pattern PRIVATE = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+|\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b|\\b[0-9a-fA-F]{24,}\\b|toolu_\\w+|\\+?\\d[\\d ()-]{9,}\\d");

    /** One text to measure: its kind (java, json, prose, transcript), the Messages array the paid API counts, and the characters the estimate is made from. */
    record Text(String name, String kind, JsonNode messages, long chars) {
        int estimate() { return TokenEstimate.tokens(chars); }
    }

    static Text plain(String name, String kind, String body) {
        var messages = JSON.createArrayNode();
        messages.addObject().put("role", "user").put("content", body);
        return new Text(name, kind, messages, body.length());
    }

    static List<Text> all(Path repo, List<Path> transcripts) throws IOException {
        var texts = new ArrayList<Text>();
        for (var f : List.of("webproxy/src/main/java/ccsuite/webproxy/TokenEstimate.java", "webproxy/src/main/java/ccsuite/webproxy/ChatSessions.java",
                "webproxy/src/main/java/ccsuite/webproxy/CCAPIProxy.java", "ccacc/src/main/java/ccsuite/ccacc/FreeEstimator.java"))
            texts.add(plain(Path.of(f).getFileName().toString(), "java", Files.readString(repo.resolve(f))));
        texts.add(plain("json-compact", "json", records(false)));
        texts.add(plain("json-pretty", "json", records(true)));
        try (var s = Files.walk(repo)) {
            var docs = s.filter(p -> p.toString().endsWith(".md") && !p.toString().contains("target") && !p.toString().contains("node_modules") && !p.toString().contains(File.separator + "prp" + File.separator))
                .filter(p -> { try { return !PRIVATE.matcher(Files.readString(p)).find(); } catch (IOException e) { return false; } })
                .sorted(Comparator.comparingLong((Path p) -> p.toFile().length()).reversed()).limit(2).toList();
            for (var d : docs) texts.add(plain(d.getFileName().toString(), "prose", Files.readString(d)));
        }
        var n = 0;
        for (var t : transcripts) texts.add(transcript("transcript-" + ++n, t));
        return texts;
    }

    /** Fake records, in the shape tool results have (ids, names, lists of numbers), compact or indented: JSON as it is in a session, with nothing real in it. */
    static String records(boolean pretty) throws IOException {
        var rnd = new Random(85);
        var all = JSON.createArrayNode();
        for (var i = 0; JSON.writeValueAsString(all).length() < JSON_CHARS; i++) {
            var o = all.addObject().put("id", "fake-%04d".formatted(i)).put("name", "item-" + Integer.toHexString(rnd.nextInt()));
            o.put("status", i % 3 == 0 ? "ok" : "pending").put("size", rnd.nextInt(100000)).put("score", Math.round(rnd.nextDouble() * 1e4) / 1e4);
            var tags = o.putArray("tags");
            for (var k = 0; k < 3; k++) tags.add("tag-" + rnd.nextInt(50));
            var values = o.putObject("values");
            for (var k = 0; k < 4; k++) values.put("v" + k, rnd.nextInt(1000));
        }
        return pretty ? JSON.writerWithDefaultPrettyPrinter().writeValueAsString(all) : JSON.writeValueAsString(all);
    }

    static String fake(String s, Map<String, String> ids) {
        var m = PRIVATE.matcher(s);
        var out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(m.group().contains("@") ? "user@example.org" : ids.computeIfAbsent(m.group(), k -> "fake-id-%04d".formatted(ids.size()))));
        return m.appendTail(out).toString();
    }

    /** The first {@link #TRANSCRIPT_CHARS} characters of a Claude Code session file, as a valid Messages array (tool calls paired with their results). */
    static Text transcript(String name, Path file) throws IOException {
        var array = JSON.createArrayNode().addAll(paired(cleaned(file, TRANSCRIPT_CHARS)));
        var request = JSON.createObjectNode().put("model", "claude-sonnet-5-5").put("max_tokens", 1);
        request.set("messages", array);
        var chars = MessageToPromptConverter.convert(LENIENT.treeToValue(request, AnthropicRequest.class)).length();
        return new Text(name, "transcript", array, chars);
    }

    /** The user and assistant messages of a session file with the private parts replaced and the not-sent blocks dropped, consecutive same-role messages merged, up to about {@code maxChars}. Not yet paired. */
    static List<JsonNode> cleaned(Path file, long maxChars) throws IOException {
        var ids = new HashMap<String, String>();
        var messages = new ArrayList<JsonNode>();
        var size = 0L;
        try (var lines = Files.lines(file)) {
            for (var line : (Iterable<String>) lines::iterator) {
                if (size > maxChars) break;
                JsonNode e;
                try { e = JSON.readTree(line); } catch (IOException x) { continue; }
                var role = e.path("type").asText();
                if (e.path("isSidechain").asBoolean() || !(role.equals("user") || role.equals("assistant"))) continue;
                var blocks = JSON.createArrayNode();
                var content = e.path("message").path("content");
                if (content.isTextual()) content = JSON.createArrayNode().add(JSON.createObjectNode().put("type", "text").put("text", content.asText()));
                for (var b : content) {
                    var block = clean(b, ids);
                    if (block != null) blocks.add(block);
                }
                if (blocks.isEmpty()) continue;
                size += blocks.toString().length();
                if (!messages.isEmpty() && messages.getLast().path("role").asText().equals(role)) ((ArrayNode) messages.getLast().get("content")).addAll(blocks);
                else messages.add(JSON.createObjectNode().put("role", role).set("content", blocks));
            }
        }
        return messages;
    }

    /** A content block with the private parts replaced, or null when it is dropped (system reminders, thinking, images, command wrappers). */
    static JsonNode clean(JsonNode b, Map<String, String> ids) {
        switch (b.path("type").asText()) {
            case "text" -> {
                var t = b.path("text").asText();
                if (t.isBlank() || t.contains("<system-reminder>") || t.startsWith("<command-") || t.startsWith("Caveat:") || t.startsWith("[Request interrupted")) return null;
                return JSON.createObjectNode().put("type", "text").put("text", fake(t, ids));
            }
            case "tool_use" -> {
                var o = JSON.createObjectNode().put("type", "tool_use").put("id", fake(b.path("id").asText(), ids)).put("name", b.path("name").asText());
                try { o.set("input", JSON.readTree(fake(b.path("input").toString(), ids))); } catch (IOException e) { return null; }
                return o;
            }
            case "tool_result" -> {
                var c = b.path("content");
                var text = c.isTextual() ? c.asText() : StreamSupport.stream(c.spliterator(), false).filter(x -> x.path("type").asText().equals("text")).map(x -> x.path("text").asText()).collect(Collectors.joining("\n"));
                return JSON.createObjectNode().put("type", "tool_result").put("tool_use_id", fake(b.path("tool_use_id").asText(), ids)).put("content", text.isBlank() ? "(no output)" : fake(text, ids));
            }
            default -> { return null; }
        }
    }

    /** Starts with a user message, every tool call answered by the next message and every result matched to a call, ends on a message nothing waits for. */
    static List<JsonNode> paired(List<JsonNode> in) { return paired(in, false); }

    /** As above; with {@code keepOrphans} a result whose call lies outside the cut (a chunk taken from the middle of a session) stays as a plain text block instead of being dropped, so a stretch of nothing but tool calls is not lost. */
    static List<JsonNode> paired(List<JsonNode> in, boolean keepOrphans) {
        var out = new ArrayList<JsonNode>();
        Set<String> waiting = Set.of();
        for (var m : in) {
            var role = m.path("role").asText();
            var kept = JSON.createArrayNode();
            for (var b : m.path("content")) {
                var type = b.path("type").asText();
                if (type.equals("tool_result") && !waiting.contains(b.path("tool_use_id").asText())) { if (keepOrphans && !b.path("content").asText().isBlank()) kept.add(JSON.createObjectNode().put("type", "text").put("text", b.path("content").asText())); continue; }
                kept.add(b);
            }
            if (role.equals("user") && !waiting.isEmpty()) {
                var answered = StreamSupport.stream(kept.spliterator(), false).filter(b -> b.path("type").asText().equals("tool_result")).map(b -> b.path("tool_use_id").asText()).collect(Collectors.toSet());
                for (var id : waiting) if (!answered.contains(id)) kept.insert(0, JSON.createObjectNode().put("type", "tool_result").put("tool_use_id", id).put("content", "(no output)"));
            }
            if (kept.isEmpty() || (out.isEmpty() && !role.equals("user"))) continue;
            waiting = role.equals("assistant") ? StreamSupport.stream(kept.spliterator(), false).filter(b -> b.path("type").asText().equals("tool_use")).map(b -> b.path("id").asText()).collect(Collectors.toSet()) : Set.of();
            out.add(JSON.createObjectNode().put("role", role).set("content", kept));
        }
        if (!waiting.isEmpty() && !out.isEmpty()) out.removeLast();   // a last assistant message that waits for a result is dropped
        return out;
    }
}
