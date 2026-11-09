package ccsuite.ccacc;

import module java.base;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * PRP 88: the size of an answer as it streams through the free lane, in characters of text, tool input and thinking (the output the gauge charges for). Fed the bytes the lane copies
 * anyway; reads only the {@code data:} lines of the event stream that carry a delta, and never changes or holds back what passes. A plain JSON answer (no stream) is measured whole at the end.
 */
final class ReplyMeter {

    static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_PLAIN = 4 << 20;

    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final ByteArrayOutputStream plain = new ByteArrayOutputStream();
    private long chars;
    private boolean stream;

    void feed(byte[] b, int off, int n) {
        for (var i = off; i < off + n; i++) {
            if (b[i] == '\n') { line(line.toString(StandardCharsets.UTF_8)); line.reset(); }
            else line.write(b[i]);
        }
        if (!stream && plain.size() + n <= MAX_PLAIN) plain.write(b, off, n);
    }

    private void line(String l) {
        if (!l.startsWith("data:")) { if (l.startsWith("event:")) stream = true; return; }
        stream = true;
        if (!l.contains("_delta")) return;
        try {
            var d = JSON.readTree(l.substring(5).trim()).path("delta");
            chars += d.path("text").asText("").length() + d.path("partial_json").asText("").length() + d.path("thinking").asText("").length();
        } catch (IOException | RuntimeException e) { /* a line that is not JSON: not counted */ }
    }

    /** The characters of the answer so far (a plain JSON answer is measured here, once it is complete). */
    long chars() {
        if (stream || plain.size() == 0) return chars;
        try {
            var total = 0L;
            for (var c : JSON.readTree(plain.toByteArray()).path("content")) total += c.path("text").asText("").length() + (c.has("input") ? c.get("input").toString().length() : 0) + c.path("thinking").asText("").length();
            return total;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }
}
