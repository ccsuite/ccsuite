package ccsuite.cclocalusagestats.cli;

import ccsuite.cclocalusagestats.ingest.TranscriptScanner;
import java.nio.file.Path;
import java.util.*;

public final class Roots {

    private Roots() {}

    /** The Claude config folders to read: the ones given, else CLAUDE_CONFIG_DIR (comma separated), else ~/.claude. */
    public static List<Path> resolve(List<Path> given) {
        if (given != null && !given.isEmpty()) return given;
        var env = System.getenv("CLAUDE_CONFIG_DIR");
        if (env == null || env.isBlank()) return List.of(TranscriptScanner.DEFAULT_ROOT);
        return Arrays.stream(env.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Path::of).toList();
    }
}
