package ccsuite.ccacc.trim;

import module java.base;

/**
 * A headless claude.exe that a test starts itself (stream-json, no session persistence, an empty scratch folder), the way the ccram measurement script does.
 * The tests trim only this process, by its pid; no other claude.exe on the machine is looked at by name or touched.
 */
final class OwnClaude implements AutoCloseable {

    final Process process;
    final AtomicInteger results = new AtomicInteger();
    final Path work;

    private OwnClaude(Process process, Path work) { this.process = process; this.work = work; }

    static Optional<Path> onPath() {
        return Arrays.stream(System.getenv("PATH").split(File.pathSeparator)).map(d -> Path.of(d, "claude.exe")).filter(Files::isRegularFile).findFirst();
    }

    static OwnClaude start() throws IOException {
        var work = Files.createTempDirectory("ccacc-trim-test");
        var pb = new ProcessBuilder(onPath().orElseThrow().toString(), "--no-session-persistence", "--model", "sonnet", "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose")
            .directory(work.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        var c = new OwnClaude(pb.start(), work);
        Thread.ofVirtual().start(() -> {
            try (var r = new BufferedReader(new InputStreamReader(c.process.getInputStream()))) {
                for (var line = r.readLine(); line != null; line = r.readLine()) if (line.contains("\"type\":\"result\"")) c.results.incrementAndGet();
            } catch (IOException ignored) {
            }
        });
        return c;
    }

    long pid() { return process.pid(); }

    Predicate<Proc> only() { return p -> p.pid() == pid(); }

    void say(String text) throws IOException {
        var out = process.getOutputStream();
        out.write(("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"" + text + "\"}}\n").getBytes());
        out.flush();
    }

    /** Waits until it is up and has been quiet (no reads or writes) for a few seconds, so the test starts from an idle session. */
    void settle() throws InterruptedException {
        var last = -2L;
        var quiet = 0;
        for (var i = 0; i < 120 && quiet < 8; i++) {
            Thread.sleep(1000);
            var now = Win.ioOps(pid());
            quiet = now == last && now >= 0 ? quiet + 1 : 0;
            last = now;
        }
        assertTrue(quiet >= 8, "the claude.exe never became quiet");
    }

    boolean waitForResult(int seconds) throws InterruptedException {
        for (var i = 0; i < seconds * 2 && results.get() < 1; i++) Thread.sleep(500);
        return results.get() >= 1;
    }

    Proc seen() { return new WinProbe().claudes().stream().filter(only()).findFirst().orElseThrow(); }

    public void close() {
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
            try (var w = Files.walk(work)) { w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        } catch (IOException | InterruptedException ignored) {
        }
    }

    private static void assertTrue(boolean b, String m) { org.junit.jupiter.api.Assertions.assertTrue(b, m); }
}
