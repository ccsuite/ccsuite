package ccsuite.ccacc;

import ccsuite.base.config.AccountEditor;
import ccsuite.base.config.CredentialsManager;
import ccsuite.base.model.CCAPIsCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CredentialsFollowerTest {

    @Test void anEditByAnotherProcessIsTakenIntoTheAppsAccounts(@TempDir Path dir) throws Exception {
        var file = dir.resolve("credentials.json");
        var m = new CredentialsManager(file);
        m.update(all -> AccountEditor.add(all, Map.of("id", "a", "name", "A", "email", "a@example.org")));
        var shared = m.load();
        var told = new AtomicInteger();
        var follower = new CredentialsFollower(m, shared, told::incrementAndGet);
        assertFalse(follower.check(), "nothing changed yet");
        new CredentialsManager(file).update(all -> AccountEditor.set(all, "a", "watchOnly", "on"));
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertTrue(follower.check());
        assertTrue(shared.getById("a").watchOnly());
        assertEquals(1, told.get());
    }

    @Test void theAccountsTakenInReplaceTheOldListWhole(@TempDir Path dir) {
        var shared = new CCAPIsCredentials();
        AccountEditor.add(shared, Map.of("id", "old", "name", "O", "email", "o@example.org"));
        var fresh = new CCAPIsCredentials();
        AccountEditor.add(fresh, Map.of("id", "new", "name", "N", "email", "n@example.org"));
        Accounts.adopt(shared, fresh);
        assertNull(shared.getById("old"));
        assertNotNull(shared.getById("new"));
    }
}
