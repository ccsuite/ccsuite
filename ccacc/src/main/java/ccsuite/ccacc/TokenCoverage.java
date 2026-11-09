package ccsuite.ccacc;

import module java.base;
import ccsuite.webproxy.TokenFeatures;

/**
 * {@code ccacc tokencheck model coverage} (PRP 85): how much Claude Code history there is, in sessions and in tokens (sized with the fitted character-kind model, no
 * API call), and how much of it the real counts in the sample log cover.
 */
final class TokenCoverage {
    static int run(Path dir, Path root) throws IOException {
        var log = dir.resolve("samples.jsonl");
        var samples = TokenModelCli.load(log, root);
        var chunks = samples.stream().filter(x -> x.set().equals("chunk")).toList();
        var names = TokenModel.SETS.get("character kinds");
        var w = TokenModel.fit(chunks.stream().map(r -> TokenFeatures.vector(r.f(), names)).toArray(double[][]::new), chunks.stream().mapToDouble(TokenModelCli.Sample::net).toArray(), 1);
        var rows = new ArrayList<Object[]>();
        var all = Files.walk(root, 2).filter(p -> p.toString().endsWith(".jsonl")).toList();
        var subagents = all.stream().filter(p -> p.toString().contains("subagents")).count();
        var empty = 0;
        for (var f : all) {
            if (f.toString().contains("subagents")) continue;
            var m = TokenCheckTexts.paired(TokenCheckTexts.cleaned(f, Long.MAX_VALUE));
            if (m.size() < 2) { empty++; continue; }
            var arr = TokenCheckTexts.JSON.createArrayNode().addAll(m);
            var feat = TokenFeatures.of(arr);
            rows.add(new Object[]{root.relativize(f).toString().replace('\\', '/'), TokenModelCli.monthOf(f), (long) feat.get("chars").doubleValue(), Math.max(1, TokenModel.dot(w, TokenFeatures.vector(feat, names))), m.size()});
        }
        var covered = samples.stream().map(TokenModelCli.Sample::file).collect(Collectors.toSet());
        var predictedChunks = samples.stream().filter(x -> x.set().equals("chunk")).collect(Collectors.groupingBy(TokenModelCli.Sample::file, Collectors.summingDouble(x -> TokenModel.dot(w, TokenFeatures.vector(x.f(), names)))));
        var counted = samples.stream().filter(x -> x.set().equals("chunk")).collect(Collectors.groupingBy(TokenModelCli.Sample::file, Collectors.summingDouble(TokenModelCli.Sample::net)));
        Files.write(dir.resolve("coverage.tsv"), rows.stream().map(r -> r[0] + "	" + r[1] + "	" + r[2] + "	" + Math.round((double) r[3]) + "	" + Math.round(counted.getOrDefault((String) r[0], 0.0)) + "	" + r[4] + "	" + Math.round(predictedChunks.getOrDefault((String) r[0], 0.0))).toList());
        var tokens = rows.stream().mapToDouble(r -> (double) r[3]).sum();
        var coveredTokens = samples.stream().mapToDouble(TokenModelCli.Sample::net).sum();
        System.out.println("session files: %d (plus %d sub-agent files, not counted); with a real conversation in them: %d; empty or only tool noise: %d".formatted(all.size() - subagents, subagents, rows.size(), empty));
        System.out.println("tokens in all those sessions, sized with the model (about 1-2%% off): %,.0f; characters %,d".formatted(tokens, rows.stream().mapToLong(r -> (long) r[2]).sum()));
        var sorted = rows.stream().mapToDouble(r -> (double) r[3]).sorted().toArray();
        System.out.println("tokens in one session: median %,.0f, 90th percentile %,.0f, largest %,.0f; over 170,000 (where the free lane asks Claude Code to compact): %d sessions".formatted(sorted[sorted.length / 2], sorted[(int) (sorted.length * 0.9)], sorted[sorted.length - 1], Arrays.stream(sorted).filter(t -> t > 170_000).count()));
        for (var m : rows.stream().map(r -> (String) r[1]).distinct().sorted().toList())
            System.out.println("  %s: %d sessions, %,.0f tokens".formatted(m, rows.stream().filter(r -> r[1].equals(m)).count(), rows.stream().filter(r -> r[1].equals(m)).mapToDouble(r -> (double) r[3]).sum()));
        System.out.println("covered by real count_tokens figures: %d sessions of %d (%.0f%%); %,.0f real tokens = %.1f%% of all tokens in the history".formatted(rows.stream().filter(r -> covered.contains((String) r[0])).count(), rows.size(), rows.stream().filter(r -> covered.contains((String) r[0])).count() * 100.0 / rows.size(), coveredTokens, coveredTokens * 100 / tokens));
        var big = rows.stream().filter(r -> (double) r[3] > 170_000).toList();
        System.out.println("sessions over 170,000 tokens: %d; of them with a long-prefix sample: %d; tokens in them beyond the first %,d characters of cleaned text: about %,.0f".formatted(big.size(), big.stream().filter(r -> samples.stream().anyMatch(s -> s.set().equals("long") && s.file().equals(r[0]))).count(), 1_200_000, big.stream().mapToDouble(r -> Math.max(0, (long) r[2] - 1_200_000) * ((double) r[3] / (long) r[2])).sum()));
        return 0;
    }

    private TokenCoverage() { }
}
