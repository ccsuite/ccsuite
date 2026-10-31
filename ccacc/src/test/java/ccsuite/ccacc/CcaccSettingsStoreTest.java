package ccsuite.ccacc;

import ccsuite.oauthswap.watch.WatchMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CcaccSettingsStoreTest {

    @Test void missingFileGivesDefaults(@TempDir Path dir) {
        var s = new CcaccSettingsStore(dir.resolve("none/settings.json")).load();
        assertTrue(s.watch() && s.widget());
        assertFalse(s.server(), "the local proxy is off until switched on");
        assertEquals(WatchMode.AUTO, s.watchMode());
    }

    @Test void whatWasSavedComesBack(@TempDir Path dir) {
        var store = new CcaccSettingsStore(dir.resolve("a/b/settings.json"));
        store.save(new CcaccSettings().server(false).watchMode(WatchMode.LOCKED));
        var s = store.load();
        assertFalse(s.server());
        assertTrue(s.watch());
        assertEquals(WatchMode.LOCKED, s.watchMode());
    }

    @Test void unreadableFileGivesDefaultsInsteadOfFailing(@TempDir Path dir) throws Exception {
        var f = dir.resolve("settings.json");
        Files.writeString(f, "{ not json");
        assertEquals(WatchMode.AUTO, new CcaccSettingsStore(f).load().watchMode());
    }

    @Test void unknownKeysFromAnotherVersionAreIgnored(@TempDir Path dir) throws Exception {
        var f = dir.resolve("settings.json");
        Files.writeString(f, "{\"watch\":false,\"somethingNew\":1}");
        assertFalse(new CcaccSettingsStore(f).load().watch());
    }
}
