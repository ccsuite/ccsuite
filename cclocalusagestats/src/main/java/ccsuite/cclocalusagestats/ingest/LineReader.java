package ccsuite.cclocalusagestats.ingest;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.function.Consumer;

public final class LineReader {

    private LineReader() {}

    /** Feeds every complete line from the byte offset on; returns the offset after the last complete line. A half-written last line is left for next time. */
    public static long read(Path file, long offset, Consumer<String> sink) throws IOException {
        try (var in = new BufferedInputStream(Files.newInputStream(file), 1 << 20)) {
            in.skipNBytes(offset);
            var buf = new ByteArrayOutputStream(1 << 16);
            var pos = offset;
            int b;
            while ((b = in.read()) >= 0) {
                if (b != '\n') { buf.write(b); continue; }
                pos += buf.size() + 1;
                if (buf.size() > 0) sink.accept(buf.toString(StandardCharsets.UTF_8));
                buf.reset();
            }
            return pos;
        }
    }
}
