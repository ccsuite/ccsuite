package ccsuite.ccgate;

import module java.base;
import com.fasterxml.jackson.databind.*;

/** Append-only jsonl files, one per UTC day. The files are the record; anything faster (ArcadeDB) is a cache over them. */
public final class Ledger {

    static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    final Path dir;

    public Ledger(Path dir) {
        this.dir = dir;
    }

    public static Ledger standard() {
        return new Ledger(GateHome.ledger());
    }

    public synchronized void append(LedgerEntry e) {
        if (e.t() == null) e.t(Instant.now().toString());
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(DAY.format(Instant.parse(e.t())) + ".jsonl"), JSON.writeValueAsString(e) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            System.err.println("ccgate: ledger write failed: " + ex.getMessage());
        }
    }

    /** Every entry from the day of {@code since} to the day of {@code until}, oldest file first. */
    public List<LedgerEntry> read(Instant since, Instant until) {
        var out = new ArrayList<LedgerEntry>();
        for (var f : files()) {
            var day = f.getFileName().toString().replace(".jsonl", "");
            if (day.compareTo(DAY.format(since)) < 0 || day.compareTo(DAY.format(until)) > 0) continue;
            try (var lines = Files.lines(f)) {
                lines.filter(l -> !l.isBlank()).forEach(l -> parse(l).ifPresent(e -> {
                    var t = Instant.parse(e.t());
                    if (!t.isBefore(since) && !t.isAfter(until)) out.add(e);
                }));
            } catch (IOException ex) {
                System.err.println("ccgate: cannot read " + f + ": " + ex.getMessage());
            }
        }
        return out;
    }

    /** Deletes day files older than {@code days}; returns how many went. */
    public int prune(int days) {
        var cutoff = DAY.format(Instant.now().minus(Duration.ofDays(days)));
        var gone = 0;
        for (var f : files())
            if (f.getFileName().toString().compareTo(cutoff) < 0) {
                try { Files.delete(f); gone++; } catch (IOException ignored) { }
            }
        return gone;
    }

    List<Path> files() {
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.jsonl")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    static Optional<LedgerEntry> parse(String line) {
        try {
            return Optional.of(JSON.readValue(line, LedgerEntry.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
