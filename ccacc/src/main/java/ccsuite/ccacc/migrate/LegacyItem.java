package ccsuite.ccacc.migrate;

import lombok.Data;
import lombok.experimental.Accessors;

import java.nio.file.Path;

/** One thing the old XML-based tools left on the machine: what it is, where it is, and what must exist before it may be archived. */
@Data
@Accessors(fluent = true, chain = true)
public class LegacyItem {

    public static final String EXE = "exe", JAR = "jar", AOT = "aot", SHORTCUT = "shortcut", SETTINGS = "settings", CREDENTIALS = "credentials",
            /** An old tool that is not retired here but still reads the old accounts file; listed so the file is kept for it. */
            DEPENDENT = "dependent";

    String kind;
    Path path;
    /** A line for the plan: which tool it belongs to. */
    String note;
    /** The JSON file that replaces this one; the item is archived only once that file exists. Null for items nothing replaces. */
    Path replacedBy;

    public static LegacyItem of(String kind, Path path, String note) {
        return new LegacyItem().kind(kind).path(path).note(note);
    }
}
