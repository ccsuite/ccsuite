package ccsuite.ccacc;

import module java.base;

/**
 * {@code ccacc tokencheck model review} (PRP 85): the periodic check, one command. Counts what is new in the session logs (chunks, then whole sessions) with the
 * free count_tokens API, saves the features of every sample, fits and scores the estimates, sizes the whole history, and writes everything it printed to
 * {@code token-model/reviews/review-DATE.txt}. Run it monthly (sessions older than about 30 days are pruned by Claude Code, and each month's new ones are what
 * keeps the numbers current); the earlier review files are the record of whether the accuracy drifts.
 */
final class TokenReview {
    static int run(Path dir, Path root, int port, String model) throws IOException {
        var reviews = Files.createDirectories(dir.resolve("reviews"));
        var report = reviews.resolve("review-" + LocalDate.now() + ".txt");
        var console = System.out;
        var file = new PrintStream(Files.newOutputStream(report), true, StandardCharsets.UTF_8);
        System.setOut(new PrintStream(new OutputStream() {
            @Override public void write(int b) { console.write(b); file.write(b); }
            @Override public void write(byte[] b, int off, int len) { console.write(b, off, len); file.write(b, off, len); }
            @Override public void flush() { console.flush(); file.flush(); }
        }, true, StandardCharsets.UTF_8));
        try {
            System.out.println("token estimate review of " + LocalDate.now() + " (model for the counts: " + model + ")");
            var log = dir.resolve("samples.jsonl");
            var a = TokenModelCli.collect(log, root, Integer.MAX_VALUE, Integer.MAX_VALUE, port, model, null, 0, 0, 0);
            var b = TokenModelCli.collect(log, root, Integer.MAX_VALUE, 1, port, model, null, 0, 0, 330_000);
            if (a != 0 || b != 0) System.out.println("a collection ended with an error; the review continues with what is counted");
            TokenModelCli.fit(dir, root, 5, 1);
            TokenCoverage.run(dir, root);
            System.out.println("review written to " + report);
            return 0;
        } finally {
            System.setOut(console);
            file.close();
        }
    }

    private TokenReview() { }
}
