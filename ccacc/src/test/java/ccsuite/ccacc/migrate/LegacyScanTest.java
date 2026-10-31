package ccsuite.ccacc.migrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LegacyScanTest {

    static Path touch(Path p) throws Exception {
        Files.createDirectories(p.getParent());
        return Files.writeString(p, "x");
    }

    static String doctor(Path jar, String app) { return "App: " + app + " 1.0\nJar: " + jar + " (1 bytes)\n"; }

    @Test void onlyTheOldXmlToolsAreFoundTheirJarsCachesAndStartupShortcuts(@TempDir Path dir) throws Exception {
        var bin = dir.resolve("bin");
        var stable = dir.resolve("stable");
        var ccu = touch(bin.resolve("ccu.exe"));
        var rebuilt = touch(bin.resolve("ccls.exe"));
        touch(stable.resolve("ccu.jar"));
        touch(stable.resolve("ccu.ab12.cd34.aot"));
        touch(stable.resolve("ccd.jar"));
        var startup = dir.resolve("startup");
        var link = touch(startup.resolve("ccu.lnk"));
        var other = touch(startup.resolve("other.lnk"));
        var doctors = Map.of(ccu, doctor(stable.resolve("ccu.jar"), "io.github.xyz-jphil:xyz-jphil-ccapis-usage_tracker"),
                rebuilt, doctor(stable.resolve("ccls.jar"), "io.github.ccsuite:ccsuite-localserver"));
        var scan = new LegacyScan(dir, List.of(bin), startup, p -> doctors.get(p), p -> p.equals(link) ? Optional.of(ccu) : Optional.of(dir.resolve("elsewhere.exe")));
        var items = scan.scan();
        var found = items.stream().map(i -> i.kind() + ":" + i.path().getFileName()).toList();
        assertEquals(List.of("exe:ccu.exe", "jar:ccu.jar", "aot:ccu.ab12.cd34.aot", "shortcut:ccu.lnk"), found);
        assertFalse(items.stream().anyMatch(i -> i.path().equals(rebuilt) || i.path().equals(other)));
    }

    @Test void theOldXmlFilesAreListedWithTheJsonThatReplacesThem(@TempDir Path dir) throws Exception {
        touch(dir.resolve("xyz-jphil/ccapis/CCAPIsCredentials.xml"));
        touch(dir.resolve("xyz-jphil/ccapis/usage_tracker/CCAPIsUsageTrackerSettings.xml"));
        var items = new LegacyScan(dir, List.of(), null, p -> null, p -> Optional.empty()).scan();
        assertEquals(2, items.size());
        assertEquals(dir.resolve("ccsuite/base/credentials.json"), items.get(0).replacedBy());
        assertEquals(dir.resolve("ccsuite/usagetracker/settings.json"), items.get(1).replacedBy());
    }

    @Test void nothingOldMeansAnEmptyScan(@TempDir Path dir) {
        assertTrue(new LegacyScan(dir, List.of(dir), dir, p -> null, p -> Optional.empty()).scan().isEmpty());
    }
}
