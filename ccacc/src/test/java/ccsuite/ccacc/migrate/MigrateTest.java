package ccsuite.ccacc.migrate;

import ccsuite.base.config.AccountEditor;
import ccsuite.base.config.CredentialsManager;
import ccsuite.base.model.CCAPIsCredentials;
import ccsuite.ccacc.CcaccSettingsStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The whole migration on a fake machine: a folder with a fake exe, jar, Startup shortcut and old XML files. */
class MigrateTest {

    static final String OLD_ACCOUNTS = "<CCAPIsCredentials>"
            + "<CCAPICredential id=\"a\" name=\"A\" email=\"a@example.org\" ccapiBaseUrl=\"x\" orgId=\"o\" key=\"k\"/>"
            + "<CCAPICredential id=\"b\" name=\"B\" email=\"b@example.org\" ccapiBaseUrl=\"x\" orgId=\"o2\" key=\"k2\"/>"
            + "</CCAPIsCredentials>";
    static final String OLD_CCU_SETTINGS = "<CCAPIsUsageTrackerSettings><ui layout=\"horizontal\" lastScreenLoc=\"5,6,7\"/></CCAPIsUsageTrackerSettings>";

    static Path file(Path p, String text) throws Exception {
        Files.createDirectories(p.getParent());
        return Files.writeString(p, text);
    }

    /** The fake machine and what the tests look at. */
    static class Fake {
        Migrate migrate;
        final List<String> out = new ArrayList<>();
        final List<Path> stopped = new ArrayList<>();
        Path cswap, jar, xml, link;
    }

    static Fake machine(Path home, boolean withOldCcd) throws Exception {
        var f = new Fake();
        var bin = home.resolve("bin");
        f.cswap = file(bin.resolve("cswap.exe"), "exe");
        f.jar = file(home.resolve("stable/cswap.jar"), "jar");
        f.link = file(home.resolve("startup/cswap.lnk"), "lnk");
        f.xml = file(home.resolve("xyz-jphil/ccapis/CCAPIsCredentials.xml"), OLD_ACCOUNTS);
        file(home.resolve("xyz-jphil/ccapis/usage_tracker/CCAPIsUsageTrackerSettings.xml"), OLD_CCU_SETTINGS);
        var current = new CCAPIsCredentials();
        AccountEditor.add(current, Map.of("id", "a", "name", "A", "email", "a@example.org"));
        new CredentialsManager(home.resolve("ccsuite/base/credentials.json")).save(current);
        var doctor = "App: io.github.xyz-jphil:xyz-jphil-ccapis-claudeswap 1.0\nJar: " + f.jar + " (3 bytes)\n";
        var oldCcd = bin.resolve("ccd.exe");
        if (withOldCcd) file(oldCcd, "exe");
        var scan = new LegacyScan(home, List.of(bin), f.link.getParent(),
                p -> p.equals(f.cswap) ? doctor : p.equals(oldCcd) ? "App: io.github.xyz-jphil:xyz-jphil-ccapis-conversation_downloader 1.0\n" : null,
                p -> Optional.of(f.cswap));
        f.migrate = new Migrate(home, scan, f.stopped::add, f.out::add);
        return f;
    }

    static Fake machine(Path home) throws Exception { return machine(home, false); }

    @Test void anOldCcdStillReadsTheAccountsFileSoThatFileStaysWhileTheRestIsArchived(@TempDir Path home) throws Exception {
        var m = machine(home, true);
        assertEquals(0, m.migrate.plan(true));
        assertTrue(Files.exists(m.xml), "the accounts file is kept for ccd");
        assertTrue(Files.exists(home.resolve("bin/ccd.exe")), "ccd itself is not retired");
        assertFalse(Files.exists(m.cswap));
        assertTrue(m.out.stream().anyMatch(l -> l.contains("Kept the old accounts file: ccd")));
    }

    @Test void aPlanChangesNothing(@TempDir Path home) throws Exception {
        var m = machine(home);
        assertEquals(0, m.migrate.plan(false));
        assertTrue(Files.exists(m.cswap));
        assertTrue(Files.exists(m.xml));
        assertTrue(m.stopped.isEmpty());
        assertTrue(m.out.stream().anyMatch(l -> l.contains("Nothing was changed")));
    }

    @Test void applyBringsTheDataIntoJsonStopsAndArchivesTheOldToolsAndTurnsOnWhatReplacesThem(@TempDir Path home) throws Exception {
        var m = machine(home);
        assertEquals(0, m.migrate.plan(true));
        assertEquals(List.of(m.cswap), m.stopped);
        assertFalse(Files.exists(m.cswap));
        assertFalse(Files.exists(m.jar));
        assertFalse(Files.exists(m.xml));
        assertFalse(Files.exists(m.link));
        var accounts = new CredentialsManager(home.resolve("ccsuite/base/credentials.json")).load();
        assertNotNull(accounts.getById("b"));
        var ccu = new ccsuite.usagetracker.config.SettingsManager(home.resolve("ccsuite/usagetracker/settings.json")).load();
        assertEquals("5,6,7", ccu.ui().lastScreenLoc());
        assertTrue(new CcaccSettingsStore(home.resolve("ccsuite/ccacc/settings.json")).load().watch());
    }

    @Test void restoreBringsEverythingBack(@TempDir Path home) throws Exception {
        var m = machine(home);
        m.migrate.plan(true);
        var stamp = new ArchiveStore(home.resolve("ccsuite/archive")).stamps().getFirst();
        assertEquals(0, m.migrate.restore(stamp));
        assertTrue(Files.exists(m.cswap));
        assertTrue(Files.exists(m.jar));
        assertTrue(Files.exists(m.xml));
    }

    @Test void whenNothingOldIsThereItSaysSo(@TempDir Path home) throws Exception {
        var out = new ArrayList<String>();
        var scan = new LegacyScan(home, List.of(), null, p -> null, p -> Optional.empty());
        assertEquals(0, new Migrate(home, scan, p -> { }, out::add).plan(true));
        assertTrue(out.getFirst().contains("nothing to migrate"));
    }
}
