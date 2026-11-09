package ccsuite.ccacc;

import module java.base;
import module java.net.http;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code ccacc tokencheck} (PRP 85, Part B): the free lane's estimate (characters over 2.4) against the paid account's own token count for the same texts.
 * A measurement you run: the paid count_tokens call goes through the paid port (pclaude) and is made only here, never by a live free-lane request, and the
 * guard and the usage block keep their own estimate. One line per text goes to {@code ~/ccsuite/webproxy/token-check.jsonl}; the graph is rebuilt from the
 * whole log into {@code token-check.html} next to it. The constant is never changed by this command.
 */
final class TokenCheck {
    static final String HELP = """
        ccacc tokencheck [--dry-run] [--graph-only] [--actual] [--port 8681] [--model claude-sonnet-5-5] [--repo DIR] [--transcripts A.jsonl,B.jsonl] [--store DIR]
          Counts a fixed set of texts (Java, JSON, prose, two session transcripts with fake ids) with the PAID token-count API through the pclaude port,
          next to the free estimate; appends one line per text to token-check.jsonl and redraws token-check.html (estimate against paid, with the 2.4 line).
          --actual      also send the smallest text of each kind as a real one-token message and record the input tokens the server reports (spends a little paid quota)
          --dry-run     list the texts with their characters and estimates; no paid call
          --graph-only  redraw the graph from the log; no paid call
        ccacc tokencheck model review                 the periodic check (run monthly): counts new session chunks and whole sessions with the free count_tokens API, saves their features
                                                      (sessions are pruned after about 30 days), fits and scores the estimates, sizes the history, writes token-model/reviews/review-DATE.txt
        ccacc tokencheck model collect|fit|coverage|snapshot   the steps of a review on their own (collect --whole 330000 counts whole sessions; fit also writes weights.json)""";

    static int run(String[] args) {
        if (args.length > 0 && args[0].equals("model")) {
            try { return TokenModelCli.run(Arrays.asList(args).subList(1, args.length), Path.of(System.getProperty("user.home"), "ccsuite", "webproxy")); }
            catch (IOException | RuntimeException e) { System.err.println("Error: " + e); return 1; }
        }
        var port = 8681;
        var model = "claude-sonnet-5-5";
        var repo = Path.of("").toAbsolutePath();
        var store = Path.of(System.getProperty("user.home"), "ccsuite", "webproxy");
        var transcripts = (List<Path>) null;
        var dry = false;
        var graphOnly = false;
        var actual = false;
        try {
            for (var i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--model" -> model = args[++i];
                    case "--repo" -> repo = Path.of(args[++i]);
                    case "--store" -> store = Path.of(args[++i]);
                    case "--transcripts" -> transcripts = Arrays.stream(args[++i].split(",")).map(Path::of).toList();
                    case "--dry-run" -> dry = true;
                    case "--graph-only" -> graphOnly = true;
                    case "--actual" -> actual = true;
                    case "-h", "--help" -> { System.out.println(HELP); return 0; }
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            Files.createDirectories(store);
            var log = store.resolve("token-check.jsonl");
            if (!graphOnly) {
                var texts = TokenCheckTexts.all(repo, transcripts != null ? transcripts : sessions());
                if (dry) {
                    texts.forEach(t -> System.out.println("%-26s %-10s %9d chars  estimate %7d tokens".formatted(t.name(), t.kind(), t.chars(), t.estimate())));
                    return 0;
                }
                measure(texts, port, model, log, actual);
            }
            var rows = TokenCheckSummary.measurements(Files.exists(log) ? Files.readAllLines(log) : List.of());
            var graph = store.resolve("token-check.html");
            Files.writeString(graph, TokenCheckSummary.html(rows));
            System.out.println(String.join("\n", TokenCheckSummary.text(rows)));
            System.out.println("log   " + log + "\ngraph " + graph);
            return 0;
        } catch (ArrayIndexOutOfBoundsException e) {
            System.err.println("Error: an option is missing its value");
            return 1;
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    /** Two transcripts of this project's own sessions: the largest session files not touched in the last two hours (never the one running now). */
    static List<Path> sessions() throws IOException {
        var dir = Path.of(System.getProperty("user.home"), ".claude", "projects", "C--user-code-ccsuite");
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".jsonl") && p.toFile().lastModified() < System.currentTimeMillis() - 2 * 3600_000L)
                .sorted(Comparator.comparingLong((Path p) -> p.toFile().length()).reversed()).limit(2).toList();
        }
    }

    /**
     * Counts every text; with {@code actual} also sends the smallest text of each kind as a real one-token message and records the input tokens the server
     * itself reports ({@code usage}), next to the count: that is what shows whether the count API is exact. The real calls spend a little paid quota.
     */
    static void measure(List<TokenCheckTexts.Text> texts, int port, String model, Path log, boolean actual) throws IOException {
        var run = Instant.now().toString();
        var http = HttpClient.newHttpClient();
        var base = TokenCheckTexts.plain("x", "baseline", "x").messages();
        // what a request costs before any text: one character, counted, less its own one token
        var overhead = count(http, port, model, base) - 1;
        var realOverhead = actual ? actual(http, port, model, base) - 1 : overhead;
        append(log, line(run, "baseline", "baseline", model, 1, 1, overhead + 1, overhead, actual ? realOverhead + 1 : null, realOverhead));
        var real = actual ? texts.stream().collect(Collectors.groupingBy(TokenCheckTexts.Text::kind, Collectors.minBy(Comparator.comparingLong(TokenCheckTexts.Text::chars)))).values().stream().map(t -> t.orElseThrow().name()).collect(Collectors.toSet()) : Set.<String>of();
        for (var t : texts) {
            try {
                var paid = count(http, port, model, t.messages());
                var real1 = real.contains(t.name()) ? actual(http, port, model, t.messages()) : null;
                append(log, line(run, t.name(), t.kind(), model, t.chars(), t.estimate(), paid, overhead, real1, realOverhead));
                System.out.println("%-26s %9d chars  estimate %7d  paid count %7d%s".formatted(t.name(), t.chars(), t.estimate(), paid, real1 == null ? "" : "  actual %7d".formatted(real1)));
            } catch (IOException e) {
                System.err.println(t.name() + ": not counted (" + e.getMessage() + ")");
            }
        }
    }

    static String line(String run, String name, String kind, String model, long chars, int estimate, int paid, int overhead, Integer actual, int actualOverhead) throws IOException {
        var net = Math.max(1, paid - overhead);
        var o = TokenCheckTexts.JSON.createObjectNode().put("ts", Instant.now().toString()).put("run", run).put("name", name).put("kind", kind).put("model", model);
        o.put("chars", chars).put("estimate", estimate).put("paid", paid).put("overhead", overhead).put("net", net).put("ratio", Math.round(chars * 1000.0 / net) / 1000.0);
        if (actual != null) o.put("actual", actual).put("actualNet", Math.max(1, actual - actualOverhead)).put("actualRatio", Math.round(chars * 1000.0 / Math.max(1, actual - actualOverhead)) / 1000.0).put("countError", paid - actual);
        return TokenCheckTexts.JSON.writeValueAsString(o);
    }

    static void append(Path log, String line) throws IOException {
        Files.writeString(log, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** The paid account's input-token count for these messages, through the paid port (the gate adds the credentials). The count and the real call carry the same system block. */
    static int count(HttpClient http, int port, String model, JsonNode messages) throws IOException {
        return call(http, port, "/v1/messages/count_tokens", model, messages, false);
    }

    /** What the server itself reports for a real one-token message: the input tokens of its {@code usage}, cached or not. A real call: it spends a little quota. */
    static int actual(HttpClient http, int port, String model, JsonNode messages) throws IOException {
        return call(http, port, "/v1/messages", model, messages, true);
    }

    static int call(HttpClient http, int port, String path, String model, JsonNode messages, boolean real) throws IOException {
        var body = TokenCheckTexts.JSON.createObjectNode().put("model", model);
        // a Claude Code login is only accepted for a real call with this system block; without it the server answers a misleading rate_limit_error
        body.putArray("system").addObject().put("type", "text").put("text", "You are Claude Code, Anthropic's official CLI for Claude.");
        if (real) body.put("max_tokens", 1);
        body.set("messages", messages);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofMinutes(3))
            .header("content-type", "application/json").header("anthropic-version", "2023-06-01").header("authorization", "Bearer ccacc-local").header("x-ccacc-job", "tokencheck")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " " + response.body().replaceAll("\\s+", " ").substring(0, Math.min(160, response.body().length())));
            var answer = TokenCheckTexts.JSON.readTree(response.body());
            var tokens = real ? answer.path("usage").path("input_tokens") : answer.path("input_tokens");
            if (!tokens.isInt()) throw new IOException("no input_tokens in the answer");
            return tokens.asInt() + (real ? answer.path("usage").path("cache_creation_input_tokens").asInt() + answer.path("usage").path("cache_read_input_tokens").asInt() : 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private TokenCheck() { }
}
