package ccsuite.base.config;

import ccsuite.base.model.CCAPIsCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** Several writers at once, each unaware of the others: every change must survive. */
class ConcurrentUpdateTest {

    @Test void writersRacingEachOtherLoseNothing(@TempDir Path dir) throws Exception {
        var file = dir.resolve("credentials.json");
        new CredentialsManager(file).save(new CCAPIsCredentials());
        var writers = 12;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(writers);
        var done = new ArrayList<Future<?>>();
        for (var i = 0; i < writers; i++) {
            var id = "acct" + i;
            // each writer has its own manager, as two processes would
            done.add(pool.submit(() -> {
                start.await();
                return new CredentialsManager(file).update(all -> AccountEditor.add(all, Map.of("id", id, "name", id, "email", id + "@example.org")));
            }));
        }
        start.countDown();
        for (var f : done) f.get();
        pool.shutdown();
        var all = new CredentialsManager(file).load();
        assertEquals(writers, all.credentials().size());
        for (var i = 0; i < writers; i++) assertNotNull(all.getById("acct" + i));
    }

    @Test void aRefusedChangeSavesNothing(@TempDir Path dir) throws Exception {
        var file = dir.resolve("credentials.json");
        var m = new CredentialsManager(file);
        m.update(all -> AccountEditor.add(all, Map.of("id", "a", "name", "A", "email", "a@example.org")));
        var before = m.lastModified();
        assertThrows(IllegalArgumentException.class, () -> m.update(all -> {
            AccountEditor.set(all, "a", "name", "changed");
            AccountEditor.set(all, "a", "tier", "lots");
        }));
        assertEquals("A", m.load().getById("a").name());
        assertEquals(before, m.lastModified());
    }

    @Test void anEditMadeElsewhereIsSeenByTheNextUpdate(@TempDir Path dir) throws Exception {
        var file = dir.resolve("credentials.json");
        var app = new CredentialsManager(file);
        var cli = new CredentialsManager(file);
        app.update(all -> AccountEditor.add(all, Map.of("id", "a", "name", "A", "email", "a@example.org")));
        cli.update(all -> AccountEditor.set(all, "a", "watchOnly", "on"));
        app.update(all -> AccountEditor.set(all, "a", "tier", "2"));
        var a = app.load().getById("a");
        assertTrue(a.watchOnly(), "the CLI's change survived the app's later one");
        assertEquals(2, a.tier());
    }
}
