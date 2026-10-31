package ccsuite.ccacc.migrate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds what the old XML-based ccu, cswap and ccls left: their exes on the PATH, the jars and AOT caches those run
 * from, the Startup shortcuts that start them, and their XML settings files. An exe is theirs when its own
 * {@code -Xjr:doctor} names the old artifact ({@code xyz-jphil-ccapis}); a rebuilt, JSON-based tool of the same name is left alone.
 * Everything outside is passed in, so the scan can be tried on a folder of fake files.
 */
public final class LegacyScan {

    public static final Set<String> TOOLS = Set.of("ccu", "cswap", "ccls");
    /** Old tools that are not retired by migrating but still read the old accounts file. */
    public static final Set<String> DEPENDENTS = Set.of("ccd");
    static final String MARK = "xyz-jphil-ccapis";
    static final Pattern JAR = Pattern.compile("^Jar:\\s*(.+?)\\s*\\(\\d+ bytes\\)\\s*$", Pattern.MULTILINE);

    final Path home, startupDir;
    final List<Path> pathDirs;
    final Function<Path, String> doctor;
    final Function<Path, Optional<Path>> shortcutTarget;

    public LegacyScan(Path home, List<Path> pathDirs, Path startupDir, Function<Path, String> doctor, Function<Path, Optional<Path>> shortcutTarget) {
        this.home = home;
        this.pathDirs = pathDirs;
        this.startupDir = startupDir;
        this.doctor = doctor;
        this.shortcutTarget = shortcutTarget;
    }

    public List<LegacyItem> scan() {
        var items = new ArrayList<LegacyItem>();
        var exes = new ArrayList<Path>();
        for (var dir : pathDirs)
            for (var tool : TOOLS) {
                var exe = dir.resolve(tool + ".exe");
                if (!Files.isRegularFile(exe) || exes.contains(exe)) continue;
                var text = doctor.apply(exe);
                if (text == null || !text.contains(MARK)) continue;
                exes.add(exe);
                items.add(LegacyItem.of(LegacyItem.EXE, exe, tool));
                jarItems(tool, text, items);
            }
        for (var dir : pathDirs)
            for (var dep : DEPENDENTS) {
                var exe = dir.resolve(dep + ".exe");
                if (Files.isRegularFile(exe) && Optional.ofNullable(doctor.apply(exe)).orElse("").contains(MARK) && items.stream().noneMatch(i -> i.path().equals(exe)))
                    items.add(LegacyItem.of(LegacyItem.DEPENDENT, exe, dep));
            }
        shortcuts(exes, items);
        var old = home.resolve("xyz-jphil").resolve("ccapis");
        add(items, LegacyItem.CREDENTIALS, old.resolve("CCAPIsCredentials.xml"), "accounts", home.resolve("ccsuite/base/credentials.json"));
        add(items, LegacyItem.SETTINGS, old.resolve("usage_tracker/CCAPIsUsageTrackerSettings.xml"), "ccu", home.resolve("ccsuite/usagetracker/settings.json"));
        add(items, LegacyItem.SETTINGS, old.resolve("cclocalserver/CCAPIsLocalServerSettings.xml"), "ccls", home.resolve("ccsuite/localserver/settings.json"));
        return items;
    }

    /** The jar the exe runs from, and the AOT caches beside it ({@code ccu.ab12.cd34.aot}). */
    void jarItems(String tool, String doctorText, List<LegacyItem> items) {
        var m = JAR.matcher(doctorText);
        if (!m.find()) return;
        var jar = Path.of(m.group(1));
        if (!Files.isRegularFile(jar)) return;
        items.add(LegacyItem.of(LegacyItem.JAR, jar, tool));
        var base = jar.getFileName().toString().replaceFirst("\\.jar$", "") + ".";
        try (Stream<Path> siblings = Files.list(jar.getParent())) {
            siblings.filter(p -> p.getFileName().toString().startsWith(base) && p.getFileName().toString().contains(".aot"))
                    .sorted().forEach(p -> items.add(LegacyItem.of(LegacyItem.AOT, p, tool)));
        } catch (IOException ignored) {
            // no cache to list: nothing to archive
        }
    }

    void shortcuts(List<Path> exes, List<LegacyItem> items) {
        if (startupDir == null || !Files.isDirectory(startupDir)) return;
        try (Stream<Path> links = Files.list(startupDir)) {
            links.filter(p -> p.getFileName().toString().endsWith(".lnk")).sorted().forEach(lnk ->
                    shortcutTarget.apply(lnk).filter(exes::contains).ifPresent(t -> items.add(LegacyItem.of(LegacyItem.SHORTCUT, lnk, t.getFileName().toString()))));
        } catch (IOException ignored) {
            // an unreadable Startup folder has nothing to report
        }
    }

    static void add(List<LegacyItem> items, String kind, Path file, String note, Path replacedBy) {
        if (Files.isRegularFile(file)) items.add(LegacyItem.of(kind, file, note).replacedBy(replacedBy));
    }
}
