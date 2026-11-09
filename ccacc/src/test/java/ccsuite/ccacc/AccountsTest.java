package ccsuite.ccacc;

import ccsuite.base.config.CredentialsManager;
import ccsuite.base.model.CCAPIsCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AccountsTest {

    record Fixture(Accounts accounts, CredentialsManager manager, AtomicInteger changes) {}

    static Fixture fixture(Path dir) {
        var manager = new CredentialsManager(dir.resolve("credentials.json"));
        var changes = new AtomicInteger();
        return new Fixture(new Accounts(manager, new CCAPIsCredentials(), changes::incrementAndGet), manager, changes);
    }

    static Map<String, String> props(String... kv) {
        var m = new LinkedHashMap<String, String>();
        for (var i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test void anAddedAccountIsSavedAndTheAppIsToldOnce(@TempDir Path dir) throws Exception {
        var f = fixture(dir);
        f.accounts().add(props("id", "cat", "name", "J", "email", "j@example.org", "trackUsage", "on"));
        assertEquals(1, f.changes().get());
        assertTrue(f.manager().load().getById("cat").trackUsage());
    }

    @Test void theLimitAndMonitorOnlyAreSetPerAccountAndSurviveAReload(@TempDir Path dir) throws Exception {
        var f = fixture(dir);
        f.accounts().add(props("id", "a", "name", "A", "email", "a@example.org"));
        f.accounts().add(props("id", "b", "name", "B", "email", "b@example.org"));
        f.accounts().set("b", props("claudecodelimit", "80", "watchOnly", "on"));
        var back = f.manager().load();
        assertEquals("80%", back.getById("b").claudecodelimit());
        assertTrue(back.getById("b").watchOnly());
        assertFalse(back.getById("a").watchOnly());
        assertEquals("95%", back.getById("a").claudecodelimit());
    }

    @Test void aRefusedValueChangesNothingOnDisk(@TempDir Path dir) throws Exception {
        var f = fixture(dir);
        f.accounts().add(props("id", "a", "name", "A", "email", "a@example.org"));
        var changes = f.changes().get();
        assertThrows(IllegalArgumentException.class, () -> f.accounts().set("a", props("claudecodelimit", "300")));
        assertEquals(changes, f.changes().get());
        assertEquals("95%", f.manager().load().getById("a").claudecodelimit());
    }

    @Test void aRemovedAccountIsGoneFromTheFile(@TempDir Path dir) throws Exception {
        var f = fixture(dir);
        f.accounts().add(props("id", "a", "name", "A", "email", "a@example.org"));
        f.accounts().remove("a");
        assertNull(f.manager().load().getById("a"));
        assertThrows(IllegalArgumentException.class, () -> f.accounts().remove("a"));
    }

    @Test void aFreeAccountIsSavedAsTierZeroWithItsKeyAndOrganisationAndNothingElseChanges(@TempDir Path dir) throws Exception {
        var f = fixture(dir);
        f.accounts().add(props("id", "paid", "name", "P", "email", "p@example.org", "tier", "5", "claudecodelimit", "80"));
        var before = f.manager().load().getById("paid");
        f.accounts().addFree("QX", "qx@example.org", "org-1234", "sk-ant-sid01-secret");
        var back = f.manager().load();
        var free = back.getById("QX");
        assertEquals(0, free.tier());
        assertEquals("org-1234", free.orgId());
        assertEquals("sk-ant-sid01-secret", free.key());
        assertEquals("QX", free.name());
        assertTrue(free.active() && free.trackUsage());
        assertEquals(before, back.getById("paid"), "another account is not touched");
        assertTrue(FreeGauges.free(back.credentials()).stream().anyMatch(c -> c.id().equals("QX")), "it counts as a free account");
    }

    @Test void aRefusedFreeAccountSaysWhyNeverShowsTheKeyAndWritesNothing(@TempDir Path dir) throws Exception {
        var f = fixture(dir);
        f.accounts().add(props("id", "paid", "name", "P", "email", "p@example.org"));
        var changes = f.changes().get();
        var secret = "sk-ant-sid01-supersecret";
        var cases = new String[][]{{"", "a@example.org", "o", secret, "short name"}, {"A B", "a@example.org", "o", secret, "short name"},
            {"A", "not-an-email", "o", secret, "e-mail"}, {"A", "a@example.org", "", secret, "organisation id"}, {"A", "a@example.org", "o", " ", "session key"},
            {"A", "a@example.org", "o", "two words", "session key"}, {"paid", "a@example.org", "o", secret, "already an account"}};
        for (var c : cases) {
            var e = assertThrows(IllegalArgumentException.class, () -> f.accounts().addFree(c[0], c[1], c[2], c[3]));
            assertTrue(e.getMessage().contains(c[4]), e.getMessage());
            assertFalse(e.getMessage().contains(secret) || e.getMessage().contains("two words"), "the key is never in a message");
        }
        assertEquals(changes, f.changes().get(), "nobody was told of a change");
        assertEquals(1, f.manager().load().credentials().size());
    }
}
