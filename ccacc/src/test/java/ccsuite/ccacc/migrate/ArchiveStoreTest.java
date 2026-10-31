package ccsuite.ccacc.migrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveStoreTest {

    static Path file(Path p, String text) throws Exception {
        Files.createDirectories(p.getParent());
        return Files.writeString(p, text);
    }

    @Test void filesGoIntoADatedFolderWithAManifestAndComeBackWhereTheyWere(@TempDir Path dir) throws Exception {
        var a = file(dir.resolve("tools/a.exe"), "AAA");
        var b = file(dir.resolve("old/b.xml"), "BBB");
        var store = new ArchiveStore(dir.resolve("archive"));
        var stamp = store.archive(List.of(LegacyItem.of("exe", a, "a"), LegacyItem.of("settings", b, "b")));
        assertFalse(Files.exists(a));
        assertFalse(Files.exists(b));
        assertEquals(List.of(stamp), store.stamps());
        assertTrue(Files.isRegularFile(store.folder(stamp).resolve("manifest.json")));
        var out = store.restore(stamp);
        assertEquals(2, out.size());
        assertEquals("AAA", Files.readString(a));
        assertEquals("BBB", Files.readString(b));
    }

    @Test void aRestoreNeverOverwritesWhatIsThereNow(@TempDir Path dir) throws Exception {
        var a = file(dir.resolve("a.exe"), "old");
        var store = new ArchiveStore(dir.resolve("archive"));
        var stamp = store.archive(List.of(LegacyItem.of("exe", a, "a")));
        file(a, "new");
        var out = store.restore(stamp);
        assertTrue(out.getFirst().contains("already at"));
        assertEquals("new", Files.readString(a));
    }

    @Test void aFileAlreadyGoneIsSkippedAndTheRestIsStillArchived(@TempDir Path dir) throws Exception {
        var b = file(dir.resolve("b.xml"), "B");
        var store = new ArchiveStore(dir.resolve("archive"));
        var stamp = store.archive(List.of(LegacyItem.of("exe", dir.resolve("gone.exe"), "x"), LegacyItem.of("settings", b, "b")));
        assertEquals(1, ArchiveStore.JSON.readValue(store.folder(stamp).resolve("manifest.json").toFile(), ArchiveStore.Manifest.class).items().size());
    }

    @Test void thereAreNoArchivesBeforeTheFirst(@TempDir Path dir) throws Exception {
        assertTrue(new ArchiveStore(dir.resolve("none")).stamps().isEmpty());
    }
}
