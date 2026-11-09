package ccsuite.ccacc;

import module java.base;

/**
 * PRP 86 R4 and R7: the one small file per Claude Code session that the free lane writes at every call and the status line (ccstatus) only reads: the serving account's short name,
 * the estimated input tokens (the number the zones judge by), the warn, close and hard points and the account's room. A session that never went through the free lane has no
 * file, so a paid or plain claude session prints what it always printed. The file is the only coupling. A later call rewrites it, so the account shown follows the lane when it
 * moves the session. Every failure is swallowed: the status file never stops a call.
 */
final class SessionStatus {

    private static final java.util.regex.Pattern PLAIN = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,100}");

    private SessionStatus() { }

    static void write(Path dir, String session, String account, long tokens, Zones zones, Instant at) {
        try {
            if (session == null || !PLAIN.matcher(session).matches()) return;
            Files.createDirectories(dir);
            var text = "account=" + (account == null || account.isBlank() ? "-" : account.trim().replaceAll("\\s", "")) + "\n"
                + "tokens=" + tokens + "\n"
                + "warn=" + zones.warn() + "\n"
                + "close=" + zones.close() + "\n"
                + "hard=" + zones.hard() + "\n"
                + "room=" + (zones.room() == Math.rint(zones.room()) ? Long.toString(Math.round(zones.room())) : String.format(Locale.ROOT, "%.1f", zones.room())) + "\n"
                + "at=" + at + "\n";
            var tmp = Files.createTempFile(dir, "." + session + "-", ".tmp");
            try {
                Files.writeString(tmp, text, java.nio.charset.StandardCharsets.UTF_8);
                Files.move(tmp, dir.resolve(session + ".txt"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            // fail open: the status line simply has nothing new to show
        }
    }
}
