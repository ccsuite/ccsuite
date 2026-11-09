package ccsuite.ccacc;

import module java.base;
import ccsuite.webproxy.TokenFeatures;
import module java.net.http;
import ccsuite.webproxy.MessageToPromptConverter;
import ccsuite.webproxy.TokenEstimate;
import ccsuite.webproxy.anthropic.AnthropicRequest;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code ccacc tokencheck model collect|fit} (PRP 85): the block-type estimate, tried on our own session logs. {@code collect} cuts chunks out of past sessions
 * (private parts replaced, as in the transcripts of the measurement), has the paid count_tokens API count each one (free, no conversation) and appends a pointer
 * to the chunk with its real count to {@code token-model/samples.jsonl}; no text is stored. {@code fit} recomputes the chunks from the session files, compares the
 * estimates (cross-validated by session) with the real counts, and says how many land within 5%.
 */
final class TokenModelCli {
    static final long MAX_CHARS = 1L << 40;   // a whole session; chunks of earlier runs were cut from the first 1,200,000 characters and are the same chunks
    static final int[] TARGETS = {6_000, 12_000, 25_000, 50_000, 90_000};
    static final Path PROJECTS = Path.of(System.getProperty("user.home"), ".claude", "projects");

    static int run(List<String> args, Path store) throws IOException {
        if (!args.isEmpty() && args.get(0).equals("gauge")) return gauge(args.subList(1, args.size()), FreeLedger.standard());
        var dir = store.resolve("token-model");
        Files.createDirectories(dir);
        var o = new HashMap<String, String>();
        for (var i = 1; i + 1 < args.size(); i += 2) o.put(args.get(i), args.get(i + 1));
        var root = Path.of(o.getOrDefault("--projects", PROJECTS.toString()));
        return switch (args.isEmpty() ? "" : args.get(0)) {
            case "collect" -> collect(dir.resolve("samples.jsonl"), root, Integer.parseInt(o.getOrDefault("--max", "300")), Integer.parseInt(o.getOrDefault("--per-file", "2")), Integer.parseInt(o.getOrDefault("--port", "8681")), o.getOrDefault("--model", "claude-haiku-5-5"), o.get("--month"), Double.parseDouble(o.getOrDefault("--min-nonascii", "0")), Integer.parseInt(o.getOrDefault("--target", "0")), Long.parseLong(o.getOrDefault("--whole", "0")));
            case "coverage" -> TokenCoverage.run(dir, root);
            case "request-check" -> TokenRequestCheck.run(dir, root, Integer.parseInt(o.getOrDefault("--port", "8681")), o.getOrDefault("--model", "claude-haiku-5-5"), Integer.parseInt(o.getOrDefault("--sessions", "16")));
            case "bodies" -> bodies(o.get("--session"), Integer.parseInt(o.getOrDefault("--port", "8681")));
            case "snapshot" -> { TokenSnapshot.refresh(dir, root); yield 0; }
            case "review" -> TokenReview.run(dir, root, Integer.parseInt(o.getOrDefault("--port", "8681")), o.getOrDefault("--model", "claude-haiku-5-5"));
            case "fit" -> fit(dir, root, Integer.parseInt(o.getOrDefault("--folds", "5")), Double.parseDouble(o.getOrDefault("--lambda", "1")));
            default -> { System.err.println("ccacc tokencheck model collect [--max 300] [--per-file 2] [--month 2026-10] [--min-nonascii 0.03] [--target 250000] [--whole 330000] [--port 8681] [--model M] | fit [--folds 5] [--lambda 1] | coverage | snapshot | review | bodies [--session ID] [--port 8681] | gauge [--since 2d] [--account ID] [--tolerance 5] [--min-points 40] [--verdict]  (--projects DIR to read other session logs)"); yield 1; }
        };
    }

    /** PRP 87 R4: the request log of the free lane (~/ccsuite/ccacc/bodies), every call counted with count_tokens on the paid lane's port and set against the estimate it was given. */
    static int bodies(String session, int port) throws IOException {
        var http = HttpClient.newHttpClient();
        var dir = Path.of(System.getProperty("user.home"), "ccsuite", "ccacc", "bodies");
        BodiesCheck.report(dir, session, body -> {
            try { var n = TokenRequestCheck.count(http, port, body); Thread.sleep(120); return n; }
            catch (IOException e) { throw new UncheckedIOException(e); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted"); }
        }).forEach(System.out::println);
        return 0;
    }

    /**
     * PRP 87: the estimate against the gauge, from the free ledger only (no count_tokens). {@code --verdict} prints the one line and exits 0 within, 2 outside, 3 too few points;
     * without it the report of days and runs ends with that line and exits 0.
     */
    static int gauge(List<String> args, FreeLedger ledger) {
        Duration since = Duration.ofDays(2); String account = null; var tolerance = 5.0; var minPoints = 40.0; var verdictOnly = false;
        for (var i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--since" -> since = CallsReport.since(args.get(++i));
                case "--account" -> account = args.get(++i);
                case "--tolerance" -> tolerance = Double.parseDouble(args.get(++i));
                case "--min-points" -> minPoints = Double.parseDouble(args.get(++i));
                case "--verdict" -> verdictOnly = true;
                default -> { System.err.println("ccacc tokencheck model gauge [--since 2d] [--account ID] [--tolerance 5] [--min-points 40] [--verdict]"); return 1; }
            }
        }
        var wanted = account;
        var runs = GaugeCheck.runs(ledger.calls(Instant.now().minus(since)).stream().filter(c -> wanted == null || wanted.equals(c.account())).toList());
        if (!verdictOnly) { GaugeCheck.report(runs, tolerance, minPoints).forEach(System.out::println); return 0; }
        var v = GaugeCheck.verdict(runs, tolerance, minPoints);
        System.out.println(GaugeCheck.verdictLine(v, tolerance));
        return switch (v.word()) { case "within" -> 0; case "outside" -> 2; default -> 3; };
    }

    static String monthOf(Path f) { return Instant.ofEpochMilli(f.toFile().lastModified()).toString().substring(0, 7); }

    static List<Path> sessions(Path root, String month) throws IOException {
        try (var s = Files.walk(root, 2)) {
            return s.filter(p -> p.toString().endsWith(".jsonl") && !p.toString().contains("subagents") && p.toFile().lastModified() < System.currentTimeMillis() - 15 * 60_000L)
                .filter(p -> month == null || monthOf(p).equals(month)).sorted().toList();
        }
    }

    /** Chunks of about a target size cut at message boundaries: start and end message index in the cleaned session. */
    static List<int[]> chunks(List<JsonNode> messages, Random rnd, int fixed) {
        var out = new ArrayList<int[]>();
        for (var start = 0; start < messages.size(); ) {
            var target = fixed > 0 ? fixed : TARGETS[rnd.nextInt(TARGETS.length)];
            var end = start;
            for (var size = 0L; end < messages.size() && size < target; end++) size += messages.get(end).toString().length();
            out.add(new int[]{start, end});
            start = end;
        }
        return out;
    }

    /** The chunk as a Messages array; {@code v2} keeps the results of calls outside the chunk as text (samples written from the fix on carry "v":2, the older ones were counted without them). */
    static JsonNode array(List<JsonNode> messages, int[] c, boolean v2) { return TokenCheckTexts.JSON.createArrayNode().addAll(TokenCheckTexts.paired(messages.subList(c[0], c[1]), v2)); }

    static double nonAscii(String s) { return s.isEmpty() ? 0 : s.codePoints().filter(x -> x > 127).count() * 1.0 / s.length(); }

    static int collect(Path log, Path root, int max, int perFile, int port, String model, String month, double minNonAscii, int target, long whole) throws IOException {
        var have = Files.exists(log) ? Files.readAllLines(log) : List.<String>of();
        var seen = have.stream().map(l -> { try { var j = TokenCheckTexts.JSON.readTree(l); return j.path("file").asText() + " " + j.path("start").asInt() + "-" + j.path("end").asInt(); } catch (IOException e) { return ""; } }).collect(Collectors.toSet());
        var files = new ArrayList<>(sessions(root, month));
        Collections.shuffle(files, new Random(85));
        var http = HttpClient.newHttpClient();
        var overhead = TokenCheck.count(http, port, model, TokenCheckTexts.plain("x", "baseline", "x").messages()) - 1;
        var done = 0;
        for (var f : files) {
            var rel = root.relativize(f).toString().replace('\\', '/');
            if (done >= max) break;
            var messages = TokenCheckTexts.cleaned(f, MAX_CHARS);
            var all = whole > 0 ? new ArrayList<>(List.of(new int[]{0, messages.size()})) : chunks(messages, new Random(rel.hashCode()), target);
            Collections.shuffle(all, new Random(rel.hashCode() + 1));
            for (var c : all.stream().filter(c -> !seen.contains(rel + " " + c[0] + "-" + c[1])).filter(c -> minNonAscii == 0 || nonAscii(array(messages, c, true).toString()) >= minNonAscii).limit(perFile).toList()) {
                var arr = array(messages, c, true);
                if (arr.size() < 2 || arr.toString().length() < (target > 0 ? target * 0.8 : 2_000) || (whole > 0 && arr.toString().length() > whole)) continue;
                try {
                    var net = TokenCheck.count(http, port, model, arr) - overhead;
                    var line = TokenCheckTexts.JSON.createObjectNode().put("file", rel).put("start", c[0]).put("end", c[1]).put("net", net).put("overhead", overhead).put("model", model).put("month", monthOf(f)).put("set", whole > 0 ? "whole" : target > 0 ? "long" : "chunk").put("v", 2).put("ts", Instant.now().toString());
                    Files.writeString(log, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    done++;
                    Thread.sleep(120);
                } catch (IOException | InterruptedException e) { System.err.println(rel + " " + c[0] + "-" + c[1] + ": " + e.getMessage()); if (e instanceof InterruptedException) return 1; }
            }
        }
        System.out.println("collected " + done + " chunks; the log has " + (have.size() + done) + " -> " + log);
        if (done > 0) TokenSnapshot.refresh(log.getParent(), root);
        return 0;
    }

    record Sample(String file, String month, String set, double net, Map<String, Double> f, double promptChars, String key) { }

    static List<Sample> load(Path log, Path root) throws IOException {
        var out = new ArrayList<Sample>();
        var snapshot = TokenSnapshot.read(log.getParent());
        var byFile = new LinkedHashMap<String, List<JsonNode>>();   // one session at a time in memory: its samples are made, then its messages are dropped
        for (var l : Files.readAllLines(log)) { var j = TokenCheckTexts.JSON.readTree(l); byFile.computeIfAbsent(j.path("file").asText(), k -> new ArrayList<>()).add(j); }
        for (var e : byFile.entrySet()) {
            var file = root.resolve(e.getKey());
            if (!Files.exists(file)) { for (var j : e.getValue()) { var kept = snapshot.get(e.getKey() + " " + j.path("start").asInt() + "-" + j.path("end").asInt()); if (kept != null) out.add(kept); } continue; }   // the session was pruned: its saved features stand in
            var messages = TokenCheckTexts.cleaned(file, MAX_CHARS);
            for (var j : e.getValue()) {
                if (messages.size() < j.path("end").asInt()) continue;
                var arr = array(messages, new int[]{j.path("start").asInt(), j.path("end").asInt()}, j.path("v").asInt(1) == 2);
                var request = TokenCheckTexts.JSON.createObjectNode().put("model", "claude-sonnet-5-5").put("max_tokens", 1);
                request.set("messages", arr);
                var chars = MessageToPromptConverter.convert(TokenCheckTexts.LENIENT.treeToValue(request, AnthropicRequest.class)).length();
                out.add(new Sample(e.getKey(), j.path("month").asText(), j.path("set").asText("chunk"), j.path("net").asDouble(), TokenFeatures.of(arr), chars, e.getKey() + " " + j.path("start").asInt() + "-" + j.path("end").asInt()));
            }
        }
        return out;
    }

    static int fit(Path dir, Path root, int folds, double lambda) throws IOException {
        var log = dir.resolve("samples.jsonl");
        if (!Files.exists(log)) { System.err.println("no samples yet: run collect first"); return 1; }
        var all = load(log, root);
        var s = all.stream().filter(x -> x.set().equals("chunk")).toList();
        var wholes = all.stream().filter(x -> x.set().equals("whole")).toList();
        var longs = all.stream().filter(x -> x.set().equals("long")).toList();
        var y = s.stream().mapToDouble(Sample::net).toArray();
        var group = s.stream().map(Sample::file).toArray(String[]::new);
        System.out.println("%d chunks from %d sessions, %d to %d real tokens each (median %.0f)".formatted(s.size(), Arrays.stream(group).distinct().count(), (long) Arrays.stream(y).min().orElse(0), (long) Arrays.stream(y).max().orElse(0), Arrays.stream(y).sorted().toArray()[y.length / 2]));
        var scores = new ArrayList<TokenModel.Score>();
        var best = (double[]) null;
        var bestName = "";
        var baseline = s.stream().mapToDouble(x -> TokenEstimate.tokens((long) x.promptChars())).toArray();
        scores.add(TokenModel.Score.of("chars / " + TokenEstimate.CHARS_PER_TOKEN + " (now)", baseline, y));
        for (var e : TokenModel.SETS.entrySet()) {
            var x = s.stream().map(r -> TokenFeatures.vector(r.f(), e.getValue())).toArray(double[][]::new);
            var predicted = TokenModel.crossValidate(x, y, group, folds, lambda);
            var score = TokenModel.Score.of(e.getKey(), predicted, y);
            if (best == null || score.meanAbs() < scores.stream().skip(1).mapToDouble(TokenModel.Score::meanAbs).min().orElse(9)) { best = predicted; bestName = e.getKey(); }
            scores.add(score);
        }
        System.out.println("each sample is a chunk of a session; estimates for a chunk never use the fit of its own session:");
        scores.forEach(sc -> System.out.println("  " + sc.line()));
        System.out.println("best: " + bestName + ". Per month (mean error, bias) for the baseline and the best:");
        for (var m : s.stream().map(Sample::month).distinct().sorted().toList()) {
            var idx = IntStream.range(0, s.size()).filter(i -> s.get(i).month().equals(m)).toArray();
            var a = TokenModel.Score.of(m + " baseline", Arrays.stream(idx).mapToDouble(i -> baseline[i]).toArray(), Arrays.stream(idx).mapToDouble(i -> y[i]).toArray());
            var bp = best;
            var b = TokenModel.Score.of(m + " best", Arrays.stream(idx).mapToDouble(i -> bp[i]).toArray(), Arrays.stream(idx).mapToDouble(i -> y[i]).toArray());
            System.out.println("  " + a.line() + "\n  " + b.line());
        }
        stability(s, y, lambda);
        wholeSessions(s, wholes, lambda);
        writeWeights(dir, s, wholes, lambda);
        if (!longs.isEmpty()) {
            var ly = longs.stream().mapToDouble(Sample::net).toArray();
            var longFiles = longs.stream().map(Sample::file).collect(Collectors.toSet());
            var train = s.stream().filter(r -> !longFiles.contains(r.file())).toList();   // no session that is tested is also in the fit
            var trainY = train.stream().mapToDouble(Sample::net).toArray();
            System.out.println("long conversations the fits have never seen (%d prefixes of %d to %d real tokens), fitted only on chunks of other sessions (%d chunks):".formatted(longs.size(), (long) Arrays.stream(ly).min().orElse(0), (long) Arrays.stream(ly).max().orElse(0), train.size()));
            System.out.println("  " + TokenModel.Score.of("chars / " + TokenEstimate.CHARS_PER_TOKEN + " (now)", longs.stream().mapToDouble(x -> TokenEstimate.tokens((long) x.promptChars())).toArray(), ly).line());
            for (var e : TokenModel.SETS.entrySet()) {
                var w = TokenModel.fit(train.stream().map(r -> TokenFeatures.vector(r.f(), e.getValue())).toArray(double[][]::new), trainY, lambda);
                System.out.println("  " + TokenModel.Score.of(e.getKey(), longs.stream().mapToDouble(r -> Math.max(1, TokenModel.dot(w, TokenFeatures.vector(r.f(), e.getValue())))).toArray(), ly).line());
                if (e.getKey().equals("character kinds")) System.out.println("    weights: " + IntStream.range(0, w.length).mapToObj(i -> e.getValue().get(i) + " " + String.format("%.4f", w[i])).collect(Collectors.joining(", ")));
            }
        }
        System.out.println("the chunks the best estimate gets most wrong (real tokens, estimate, share of non-ASCII characters, share of each block class):");
        var bw = best;
        IntStream.range(0, s.size()).boxed().sorted(Comparator.comparingDouble((Integer i) -> -Math.abs(bw[i] - y[i]) / y[i])).limit(8).forEach(i -> {
            var f = s.get(i).f();
            var chars = f.getOrDefault("chars", 1.0);
            System.out.println("  real %6.0f  estimate %6.0f  (%+5.1f%%)  non-ASCII %4.1f%%  %s".formatted(y[i], bw[i], (bw[i] - y[i]) / y[i] * 100, f.getOrDefault("nonascii", 0.0) / chars * 100,
                TokenFeatures.CLASSES.stream().map(c -> "%s %.0f%%".formatted(c, f.getOrDefault(c + ".chars", 0.0) / chars * 100)).collect(Collectors.joining("  "))));
        });
        return 0;
    }

    /** The decisive check: whole sessions counted in ONE call (no cut points), each predicted by a fit that never saw a chunk of its own session. */
    /** The weights of the estimate, fitted on every chunk and every whole session: what the request path reads. {@code weights.json} names its features, so a reader needs no other file. */
    static void writeWeights(Path dir, List<Sample> chunks, List<Sample> wholes, double lambda) throws IOException {
        var all = Stream.concat(chunks.stream(), wholes.stream()).toList();
        var out = TokenCheckTexts.JSON.createObjectNode().put("fittedOn", LocalDate.now().toString()).put("samples", all.size()).put("tokensPerChar", 0);
        out.remove("tokensPerChar");
        var sets = out.putObject("sets");
        for (var name : List.of("character kinds", "kinds + structure", "block classes + kinds")) {
            var names = TokenModel.SETS.get(name);
            var w = TokenModel.fit(all.stream().map(r -> TokenFeatures.vector(r.f(), names)).toArray(double[][]::new), all.stream().mapToDouble(Sample::net).toArray(), lambda);
            var o = sets.putObject(name);
            for (var i = 0; i < names.size(); i++) o.put(names.get(i), Math.round(w[i] * 1e6) / 1e6);
        }
        Files.writeString(dir.resolve("weights.json"), TokenCheckTexts.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        System.out.println("weights fitted on %d samples -> %s".formatted(all.size(), dir.resolve("weights.json")));
    }

    static void wholeSessions(List<Sample> chunks, List<Sample> wholes, double lambda) {
        if (wholes.isEmpty()) return;
        var sets = new LinkedHashMap<String, List<String>>();
        sets.put("one ratio (fitted)", TokenModel.SETS.get("one ratio (fitted)"));
        sets.put("character kinds", TokenModel.SETS.get("character kinds"));
        sets.put("kinds + structure", TokenModel.SETS.get("kinds + structure"));
        sets.put("block classes + kinds", TokenModel.SETS.get("block classes + kinds"));
        var wy = wholes.stream().mapToDouble(Sample::net).toArray();
        var files = wholes.stream().map(Sample::file).distinct().sorted().toList();
        var folds = 5;
        System.out.println("WHOLE SESSIONS counted in one call: %d sessions, %,.0f real tokens in all (%d to %d each); fitted only on chunks of other sessions (5 folds):".formatted(wholes.size(), Arrays.stream(wy).sum(), (long) Arrays.stream(wy).min().orElse(0), (long) Arrays.stream(wy).max().orElse(0)));
        var base = wholes.stream().mapToDouble(x -> TokenEstimate.tokens((long) x.promptChars())).toArray();
        System.out.println("  " + TokenModel.Score.of("chars / " + TokenEstimate.CHARS_PER_TOKEN + " (now)", base, wy).line());
        var perSession = IntStream.range(0, wholes.size()).mapToDouble(i -> wholes.get(i).promptChars() / wy[i]).sorted().toArray();
        var totalChars = wholes.stream().mapToDouble(Sample::promptChars).sum();
        System.out.println("  one ratio as a fallback: characters per real token over all whole sessions %.3f; per session 10th/25th/50th/75th/90th percentile %.2f / %.2f / %.2f / %.2f / %.2f".formatted(totalChars / Arrays.stream(wy).sum(), perSession[perSession.length / 10], perSession[perSession.length / 4], perSession[perSession.length / 2], perSession[perSession.length * 3 / 4], perSession[perSession.length * 9 / 10]));
        for (var r : new double[]{2.25, 2.3, 2.4, 2.5, 2.6}) {
            var est = IntStream.range(0, wholes.size()).mapToDouble(i -> wholes.get(i).promptChars() / r).toArray();
            System.out.println("    ratio %.2f: total %+5.1f%%, mean error %4.1f%%, sessions under-estimated %3.0f%%, within 5%% %3.0f%%".formatted(r, (Arrays.stream(est).sum() / Arrays.stream(wy).sum() - 1) * 100, TokenModel.Score.of("r", est, wy).meanAbs() * 100, IntStream.range(0, est.length).filter(i -> est[i] < wy[i]).count() * 100.0 / est.length, TokenModel.Score.of("r", est, wy).within5() * 100));
        }
        for (var e : sets.entrySet()) for (var withWholes : new boolean[]{false, true}) {
            if (withWholes && e.getKey().startsWith("one ratio")) continue;
            var p = new double[wholes.size()];
            for (var fold = 0; fold < folds; fold++) {
                var f = fold;
                var test = files.stream().filter(n -> Math.floorMod(files.indexOf(n), folds) == f).collect(Collectors.toSet());
                var train = Stream.concat(chunks.stream(), withWholes ? wholes.stream() : Stream.<Sample>empty()).filter(r -> !test.contains(r.file())).toList();
                var w = TokenModel.fit(train.stream().map(r -> TokenFeatures.vector(r.f(), e.getValue())).toArray(double[][]::new), train.stream().mapToDouble(Sample::net).toArray(), lambda);
                for (var i = 0; i < wholes.size(); i++) if (test.contains(wholes.get(i).file())) p[i] = Math.max(1, TokenModel.dot(w, TokenFeatures.vector(wholes.get(i).f(), e.getValue())));
            }
            System.out.println("  " + TokenModel.Score.of(e.getKey() + (withWholes ? " +whole" : ""), p, wy).line());
            if (e.getKey().equals("block classes + kinds") && withWholes) {
                var pp = p;
                System.out.println("    safety factor for the content estimate (whole sessions): " + IntStream.of(100, 102, 103, 105).mapToObj(sf -> "x%.2f: under-estimated %.0f%%, within 5%% %.0f%%, total %+.1f%%".formatted(sf / 100.0, IntStream.range(0, pp.length).filter(i -> pp[i] * sf / 100.0 < wy[i]).count() * 100.0 / pp.length, TokenModel.Score.of("s", Arrays.stream(pp).map(v -> v * sf / 100.0).toArray(), wy).within5() * 100, (Arrays.stream(pp).sum() * sf / 100.0 / Arrays.stream(wy).sum() - 1) * 100)).collect(Collectors.joining("; ")));
            }
            if (e.getKey().equals("kinds + structure") && withWholes || e.getKey().equals("character kinds") && !withWholes) {
                System.out.println("  details for " + e.getKey() + (withWholes ? " +whole" : "") + ":");
                System.out.println("    in total: real %,.0f tokens, estimated %,.0f (%+.2f%%); the same total at chars / %s: %,.0f (%+.1f%%)".formatted(Arrays.stream(wy).sum(), Arrays.stream(p).sum(), (Arrays.stream(p).sum() / Arrays.stream(wy).sum() - 1) * 100, TokenEstimate.CHARS_PER_TOKEN, Arrays.stream(base).sum(), (Arrays.stream(base).sum() / Arrays.stream(wy).sum() - 1) * 100));
                for (var b : new double[][]{{0, 10_000}, {10_000, 30_000}, {30_000, 60_000}, {60_000, 1e9}}) {
                    var idx = IntStream.range(0, wholes.size()).filter(i -> wy[i] >= b[0] && wy[i] < b[1]).toArray();
                    if (idx.length > 0) System.out.println("    " + TokenModel.Score.of("%,.0f to %,.0f tokens".formatted(b[0], Math.min(b[1], 999_999)), Arrays.stream(idx).mapToDouble(i -> p[i]).toArray(), Arrays.stream(idx).mapToDouble(i -> wy[i]).toArray()).line());
                }
                for (var m : wholes.stream().map(Sample::month).distinct().sorted().toList()) {
                    var idx = IntStream.range(0, wholes.size()).filter(i -> wholes.get(i).month().equals(m)).toArray();
                    System.out.println("    " + TokenModel.Score.of(m, Arrays.stream(idx).mapToDouble(i -> p[i]).toArray(), Arrays.stream(idx).mapToDouble(i -> wy[i]).toArray()).line());
                }
            }
        }
    }

    /** How steady the character-kind estimate is: trained on one month and tested on the other, by size of the chunk, and by project. */
    static void stability(List<Sample> s, double[] y, double lambda) {
        var names = TokenModel.SETS.get("character kinds");
        var x = s.stream().map(r -> TokenFeatures.vector(r.f(), names)).toArray(double[][]::new);
        var months = s.stream().map(Sample::month).distinct().sorted().toList();
        System.out.println("character kinds, trained on one month and tested on another (nothing of the tested month in the fit):");
        for (var test : months) {
            var tr = IntStream.range(0, s.size()).filter(i -> !s.get(i).month().equals(test)).toArray();
            var te = IntStream.range(0, s.size()).filter(i -> s.get(i).month().equals(test)).toArray();
            if (tr.length == 0 || te.length == 0) continue;
            var w = TokenModel.fit(Arrays.stream(tr).mapToObj(i -> x[i]).toArray(double[][]::new), Arrays.stream(tr).mapToDouble(i -> y[i]).toArray(), lambda);
            System.out.println("  tested on " + TokenModel.Score.of(test + " (fit on the rest)", Arrays.stream(te).mapToDouble(i -> Math.max(1, TokenModel.dot(w, x[i]))).toArray(), Arrays.stream(te).mapToDouble(i -> y[i]).toArray()).line());
        }
        var predicted = TokenModel.crossValidate(x, y, s.stream().map(Sample::file).toArray(String[]::new), 5, lambda);
        System.out.println("by size of the chunk (cross-validated by session):");
        for (var b : new double[][]{{0, 5_000}, {5_000, 20_000}, {20_000, 60_000}, {60_000, 1e9}}) {
            var idx = IntStream.range(0, s.size()).filter(i -> y[i] >= b[0] && y[i] < b[1]).toArray();
            if (idx.length > 0) System.out.println("  " + TokenModel.Score.of("%,.0f to %,.0f tokens".formatted(b[0], Math.min(b[1], 999_999)), Arrays.stream(idx).mapToDouble(i -> predicted[i]).toArray(), Arrays.stream(idx).mapToDouble(i -> y[i]).toArray()).line());
        }
        System.out.println("by project (the project folder of the session; names shortened to a number):");
        var projects = s.stream().collect(Collectors.groupingBy(r -> r.file().substring(0, r.file().indexOf('/')), TreeMap::new, Collectors.counting()));
        var n = 0;
        for (var p : projects.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(8).toList()) {
            var idx = IntStream.range(0, s.size()).filter(i -> s.get(i).file().startsWith(p.getKey() + "/")).toArray();
            System.out.println("  " + TokenModel.Score.of("project " + ++n, Arrays.stream(idx).mapToDouble(i -> predicted[i]).toArray(), Arrays.stream(idx).mapToDouble(i -> y[i]).toArray()).line());
        }
    }

    private TokenModelCli() { }
}
