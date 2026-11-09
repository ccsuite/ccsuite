package ccsuite.ccacc;

import module java.base;
import ccsuite.webproxy.ChatSessions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * PRP 87: what Claude Code sent to the free lane, one jsonl file per day ({@code bodies-YYYY-MM-DD.jsonl}), one {@code call} line per call. Incremental, like Claude Code's own transcript:
 * a call line carries only the messages new since the previous call of its session ({@code earlier} says how many came before, {@code earlierHash} which they were); the system prompt and the tool
 * definitions are {@code def} lines written once per file and named by hash; a history that no longer extends the previous one writes {@code reset} with the whole list. {@link #replay} rebuilds the
 * exact request of every call. Each call is one synchronized append of whole lines; a new day starts from whole requests and compresses the days before it and removes those past the retention.
 * The text is private conversation: the caller keeps the folder outside any repository and the log off unless the user turned it on. Nothing here ever throws into a call (fail open).
 */
final class BodyLog {

    static final ObjectMapper J = new ObjectMapper();
    static final Pattern FILE = Pattern.compile("bodies-(\\d{4}-\\d{2}-\\d{2})\\.jsonl(\\.gz)?");

    final Path dir;
    final int retentionDays;
    private LocalDate day;
    private final Set<String> written = new HashSet<>();
    private final Map<String, Integer> counts = new HashMap<>();
    private final Map<String, String> hashes = new HashMap<>();

    BodyLog(Path dir, int retentionDays) { this.dir = dir; this.retentionDays = retentionDays; }

    /** Appends the lines of this call; the zone and note are what the lane decided for it. */
    synchronized void record(byte[] body, Instant t, String account, long tokens, String zone, String note) {
        try {
            var b = (ObjectNode) J.readTree(body);
            var d = LocalDate.ofInstant(t, ZoneOffset.UTC);
            if (!d.equals(day)) newDay(d);
            var out = new StringBuilder();
            var session = ChatSessions.sessionIdOf(b.path("metadata").path("user_id").asText(""));
            if (session.isEmpty()) session = "-";
            var system = def(out, "system", b.get("system"));
            var tools = def(out, "tools", b.get("tools"));
            var messages = (ArrayNode) b.get("messages");
            var prev = counts.get(session);
            var extends_ = prev != null && messages.size() >= prev && hash(messages, prev).equals(hashes.get(session));
            var earlier = extends_ ? prev : 0;
            var params = b.deepCopy();
            params.remove(List.of("system", "tools", "messages"));
            var call = J.createObjectNode().put("type", "call").put("t", t.toString()).put("session", session).put("account", account).put("model", b.path("model").asText())
                .put("tokens", tokens).put("chars", body.length).put("zone", zone).put("kept", true);
            if (note != null) call.put("note", note);
            if (system != null) call.put("systemHash", system);
            if (tools != null) call.put("toolsHash", tools);
            call.put("earlier", earlier).put("earlierHash", earlier == 0 ? "" : hashes.get(session)).put("reset", prev != null && !extends_);
            call.set("params", params);
            var fresh = call.putArray("messages");
            for (var i = earlier; i < messages.size(); i++) fresh.add(messages.get(i));
            out.append(call).append('\n');
            Files.writeString(dir.resolve("bodies-" + d + ".jsonl"), out, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            counts.put(session, messages.size());
            hashes.put(session, hash(messages, messages.size()));
        } catch (Exception e) {
            System.err.println("ccacc: body log: " + e);
        }
    }

    private String def(StringBuilder out, String kind, JsonNode value) {
        if (value == null) return null;
        var h = hash(value.toString());
        if (written.add(h)) {
            var line = J.createObjectNode().put("type", "def").put("kind", kind).put("hash", h);
            line.set("value", value);
            out.append(line.toString()).append('\n');
        }
        return h;
    }

    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** The hash of the first {@code n} messages. */
    static String hash(ArrayNode messages, int n) {
        var sb = new StringBuilder();
        for (var i = 0; i < n; i++) sb.append(messages.get(i)).append('\n');
        return hash(sb.toString());
    }

    /** A new day: its file stands alone, the days before it are compressed and the ones past the retention removed. */
    private void newDay(LocalDate d) throws IOException {
        Files.createDirectories(dir);
        written.clear(); counts.clear(); hashes.clear();
        try (var s = Files.list(dir)) {
            for (var p : s.toList()) {
                var m = FILE.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                var date = LocalDate.parse(m.group(1));
                if (date.isBefore(d.minusDays(retentionDays))) Files.delete(p);
                else if (m.group(2) == null && date.isBefore(d)) compress(p);
            }
        }
        day = d;
    }

    private static void compress(Path p) throws IOException {
        var gz = p.resolveSibling(p.getFileName() + ".gz");
        try (var out = new GZIPOutputStream(Files.newOutputStream(gz))) { out.write(Files.readAllBytes(p)); }
        Files.delete(p);
    }

    /** Every call of a session, oldest first, as the whole request that was sent: the lines of all days are replayed (compressed days too); a call whose earlier messages are no longer in the log is left out. */
    static List<ReplayedCall> replay(Path dir, String session) throws IOException {
        var files = new ArrayList<Path>();
        try (var s = Files.list(dir)) { s.filter(p -> FILE.matcher(p.getFileName().toString()).matches()).sorted().forEach(files::add); }
        var defs = new HashMap<String, JsonNode>();
        var out = new ArrayList<ReplayedCall>();
        var current = J.createArrayNode();
        for (var f : files) {
            for (var line : lines(f)) {
                JsonNode j;
                try { j = J.readTree(line); } catch (IOException e) { continue; }
                var type = j.path("type").asText();
                if (type.equals("def")) defs.put(j.path("hash").asText(), j.path("value"));
                else if (type.equals("call") && j.path("session").asText().equals(session)) {
                    var earlier = j.path("earlier").asInt();
                    if (earlier > current.size()) continue;
                    while (current.size() > earlier) current.remove(current.size() - 1);
                    current.addAll((ArrayNode) j.path("messages"));
                    var request = ((ObjectNode) j.path("params")).deepCopy();
                    if (defs.containsKey(j.path("systemHash").asText())) request.set("system", defs.get(j.path("systemHash").asText()));
                    if (defs.containsKey(j.path("toolsHash").asText())) request.set("tools", defs.get(j.path("toolsHash").asText()));
                    request.set("messages", current.deepCopy());
                    out.add(new ReplayedCall().request(request).meta(j));
                }
            }
        }
        return out;
    }

    /** The sessions that have a call in the log, in the order of their first call. */
    static List<String> sessions(Path dir) throws IOException {
        var out = new LinkedHashSet<String>();
        if (!Files.isDirectory(dir)) return List.of();
        var files = new ArrayList<Path>();
        try (var s = Files.list(dir)) { s.filter(p -> FILE.matcher(p.getFileName().toString()).matches()).sorted().forEach(files::add); }
        for (var f : files) for (var line : lines(f)) {
            try { var j = J.readTree(line); if (j.path("type").asText().equals("call")) out.add(j.path("session").asText()); } catch (IOException e) { /* a damaged line is skipped */ }
        }
        return List.copyOf(out);
    }

    private static List<String> lines(Path f) {
        try {
            if (!f.toString().endsWith(".gz")) return Files.readAllLines(f, StandardCharsets.UTF_8);
            try (var in = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(f)), StandardCharsets.UTF_8))) { return in.lines().toList(); }
        } catch (IOException e) {
            return List.of();
        }
    }
}
