package ccsuite.ccacc;

import module java.base;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * PRP 86 R2: the Stop hook of the free lane (wired through fclaude-settings.json only, so a paid or plain claude session never runs it). It blocks the stop of a session while a
 * close order is open (the lane logged a {@code close} for it, see {@link CloseNotes}) and the cycle is not closed: closed means {@code proj prp logs} and {@code proj prp instructions}
 * were both run (as tool calls, not mentioned in text) after the order. The number of blocks per order is capped, so a session can never be stuck. No hibernation, no automatic close:
 * the session is told what is missing and does it itself. Every error fails open (empty answer, exit 0).
 */
final class StopGate {

    static final String LOGS = "proj prp logs", INSTRUCTIONS = "proj prp instructions";
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Entry(Instant at, String kind) {}

    /** The reason to give for blocking the stop, or null (let it stop). */
    static String reason(String session, List<String> log, List<String> transcript, int maxBlocks) {
        try {
            var a = analyse(session, log, transcript, maxBlocks);
            return a == null || a.isEmpty() ? null : message(a);
        } catch (Exception e) {
            return null;
        }
    }

    /** null: no open order (or the cap is reached). Empty: the order is open and both commands ran. Else: the steps still missing. */
    private static List<String> analyse(String session, List<String> log, List<String> transcript, int maxBlocks) {
        if (session == null || session.isBlank() || log == null) return null;
        var mine = new ArrayList<Entry>();
        for (var line : log) {
            try {
                var parts = line.strip().split(" ");
                if (parts.length < 3 || !("session=" + session).equals(parts[parts.length - 1])) continue;
                mine.add(new Entry(Instant.parse(parts[0]), parts[1]));
            } catch (RuntimeException e) { /* a line that is not a decision: skipped */ }
        }
        var lastClose = -1;
        for (var i = 0; i < mine.size(); i++) if (mine.get(i).kind().equals("close")) lastClose = i;
        if (lastClose < 0) return null;
        var blocks = 0;
        for (var i = lastClose + 1; i < mine.size(); i++) {
            var kind = mine.get(i).kind();
            if (kind.equals("closed")) return null;
            if (kind.equals("block")) blocks++;
        }
        if (blocks >= maxBlocks) return null;
        var since = mine.get(lastClose).at();
        var missing = new ArrayList<>(List.of(LOGS, INSTRUCTIONS));
        for (var line : transcript == null ? List.<String>of() : transcript) {
            try {
                var j = JSON.readTree(line);
                if (!"assistant".equals(j.path("type").asText()) || Instant.parse(j.path("timestamp").asText()).isBefore(since)) continue;
                for (var block : j.path("message").path("content")) {
                    if (!"tool_use".equals(block.path("type").asText())) continue;
                    var command = block.path("input").path("command").asText("");
                    missing.removeIf(command::contains);
                }
            } catch (Exception e) { /* a line that is not a transcript entry: skipped */ }
        }
        return missing;
    }

    private static String message(List<String> missing) {
        return "fclaude: the close order of this conversation is open and the cycle is not closed. Update the PRP status in this conversation now (it is hot in the cache), "
                + "then run for real: " + String.join(" and ", missing.stream().map(c -> "`" + c + "`").toList()) + ". Then say the cycle is closed.";
    }

    /** The hook: the JSON Claude Code gives on stdin in, the JSON answer (or an empty string: let it stop) out. Appends a block, or a closed line, to the decision log. Never throws. */
    static String hook(String stdin, Path log, int maxBlocks) {
        try {
            var in = JSON.readTree(stdin);
            var session = in.path("session_id").asText("");
            if (session.isEmpty() || !Files.isRegularFile(log)) return "";
            var path = in.path("transcript_path").asText("");
            var transcript = path.isEmpty() || !Files.isRegularFile(Path.of(path)) ? List.<String>of() : Files.readAllLines(Path.of(path), StandardCharsets.UTF_8);
            var missing = analyse(session, Files.readAllLines(log, StandardCharsets.UTF_8), transcript, maxBlocks);
            if (missing == null) return "";
            if (missing.isEmpty()) { append(log, "closed", session); return ""; }
            append(log, "block", session);
            return JSON.createObjectNode().put("decision", "block").put("reason", message(missing)).toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void append(Path log, String kind, String session) throws IOException {
        Files.writeString(log, Instant.now() + " " + kind + " session=" + session + "\n", StandardOpenOption.APPEND);
    }
}
