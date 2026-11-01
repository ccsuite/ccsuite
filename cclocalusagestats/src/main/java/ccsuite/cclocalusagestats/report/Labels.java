package ccsuite.cclocalusagestats.report;

import java.nio.file.Path;

public final class Labels {

    private Labels() {}

    public static String or(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    /** The last folder of a working directory, which is what a person calls the project. */
    public static String project(String cwd) {
        if (cwd == null || cwd.isBlank()) return "(unknown)";
        var name = Path.of(cwd.replace('\\', '/')).getFileName();
        return name == null ? cwd : name.toString();
    }
}
