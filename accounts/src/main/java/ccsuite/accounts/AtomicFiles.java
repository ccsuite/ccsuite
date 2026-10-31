package ccsuite.accounts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Atomic file writes plus the JSON-object text surgery used to update a single key
 * inside Claude Code's huge {@code ~/.claude.json} without re-serializing the whole
 * file (which would collapse its case-duplicate keys and reorder everything).
 *
 * A running Claude Code session re-reads these files at any moment, so every write
 * lands via a same-directory temp file + atomic rename — it never observes a partial
 * write.
 */
public final class AtomicFiles {

    private AtomicFiles() {}

    public static String readString(Path file) throws IOException {
        return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    }

    /** Write {@code content} to {@code target} atomically (temp in same dir, then rename). */
    public static void writeString(Path target, String content) throws IOException {
        var dir = target.getParent();
        if (dir != null) Files.createDirectories(dir);
        var tmp = Files.createTempFile(dir, ".cswap-", ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Find the {@code [start,end)} span of the object value (the braces and their
     * contents) for top-level-ish key {@code "key"} in {@code json}, or {@code null}
     * if not found. Brace-matched, string/escape aware.
     */
    public static int[] objectValueSpan(String json, String key) {
        var needle = "\"" + key + "\"";
        var k = json.indexOf(needle);
        if (k < 0) return null;
        var i = json.indexOf('{', k + needle.length());
        if (i < 0) return null;
        var depth = 0; var inStr = false; var esc = false;
        for (var p = i; p < json.length(); p++) {
            var c = json.charAt(p);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
            } else if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return new int[]{i, p + 1}; }
        }
        return null;
    }

    /** Re-indent a pretty-printed JSON block so every line after the first gets {@code indent}. */
    public static String reindent(String pretty, String indent) {
        return pretty.replace("\n", "\n" + indent);
    }
}
