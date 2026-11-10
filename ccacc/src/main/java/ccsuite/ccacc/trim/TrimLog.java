package ccsuite.ccacc.trim;

import module java.base;

/** One line per trim, appended to {@code ~/ccsuite/ccacc/trim.log}: pid, session name if known, working set and private bytes before and after, and the MB given back today. */
final class TrimLog {

    static Path file() { return TrimJson.home().resolve("trim.log"); }

    static String line(Outcome o, Instant at) {
        return String.format(Locale.ROOT, "%s trim pid=%d session=%s ws %.1f->%.1f MB private %.1f->%.1f MB given=%.1f MB today=%.1f MB",
            at, o.pid(), o.name().isEmpty() ? "-" : o.name(), mb(o.wsBefore()), mb(o.wsAfter()), mb(o.privBefore()), mb(o.privAfter()), mb(o.wsBefore() - o.wsAfter()), o.todayMb());
    }

    static void append(Path file, Outcome o, Instant at) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line(o, at) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("ccacc trim: cannot write " + file + ": " + e.getMessage());
        }
    }

    static double mb(long bytes) { return bytes / 1_048_576.0; }

    private TrimLog() {}
}
