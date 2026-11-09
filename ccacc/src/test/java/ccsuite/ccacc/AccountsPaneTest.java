package ccsuite.ccacc;

import ccsuite.base.config.CredentialsManager;
import ccsuite.base.model.CCAPIsCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** The Manage tab: a free account shows only what applies to it, and its session key and organisation id can be replaced without touching anything else. */
class AccountsPaneTest {

    static <T> T onFx(java.util.function.Supplier<T> s) throws Exception { return MainWindowTest.onFx(s); }

    record Fixture(AccountsPane pane, Accounts accounts, CredentialsManager manager) { }

    static Fixture fixture(Path dir) throws Exception {
        MainWindowTest.toolkit();
        var manager = new CredentialsManager(dir.resolve("credentials.json"));
        var accounts = new Accounts(manager, new CCAPIsCredentials(), () -> { });
        var p = new LinkedHashMap<String, String>();
        p.put("id", "PAID"); p.put("name", "Paid"); p.put("email", "p@example.org"); p.put("tier", "5"); p.put("trackUsage", "on");
        accounts.add(p);
        accounts.addFree("QX", "qx@example.org", "org-1", "key-old");
        var parts = MainWindowTest.parts(dir, new WatchBridge());
        var pane = onFx(() -> new AccountsPane(parts, accounts));
        onFx(() -> { pane.start(); return null; });
        return new Fixture(pane, accounts, manager);
    }

    static void choose(Fixture f, String id) throws Exception {
        onFx(() -> { f.pane().list.getSelectionModel().select(f.pane().idsNow().indexOf(id)); return null; });
    }

    @Test void aFreeAccountShowsOnlyWhatAppliesToItAndAPaidOneShowsEverything(@TempDir Path dir) throws Exception {
        var f = fixture(dir);
        choose(f, "QX");
        assertTrue(onFx(() -> f.pane().include.isDisable() && f.pane().track.isDisable() && f.pane().primary.isDisable() && f.pane().limit.isDisable()),
            "the free state follows its restore time: these flags play no part for a free account");
        assertFalse(onFx(() -> f.pane().monitorOnly.isDisable()), "monitor only still applies to it");
        assertFalse(onFx(() -> f.pane().orgId.isDisable() || f.pane().key.isDisable()));
        choose(f, "PAID");
        assertFalse(onFx(() -> f.pane().include.isDisable() || f.pane().track.isDisable() || f.pane().primary.isDisable() || f.pane().limit.isDisable()));
    }
    @Test void theSessionKeyIsShownAsItIsSavedAndReplacedOnlyWhenChangedAndNeverPutInAMessage(@TempDir Path dir) throws Exception {
        var f = fixture(dir);
        choose(f, "QX");
        assertEquals("key-old", onFx(() -> f.pane().key.getText()), "the saved key is visible in the form, not hidden");
        assertFalse(onFx(() -> f.pane().key instanceof javafx.scene.control.PasswordField), "an ordinary text field");
        var paidBefore = f.manager().load().getById("PAID");
        onFx(() -> { f.pane().apply(); return null; });
        assertEquals("key-old", f.manager().load().getById("QX").key(), "an unchanged key stays");
        assertEquals("org-1", f.manager().load().getById("QX").orgId());
        onFx(() -> { f.pane().key.setText("key-new"); f.pane().orgId.setText("org-2"); f.pane().apply(); return null; });
        var back = f.manager().load();
        assertEquals("key-new", back.getById("QX").key());
        assertEquals("org-2", back.getById("QX").orgId());
        assertEquals(paidBefore, back.getById("PAID"), "another account is untouched");
        assertFalse(onFx(() -> f.pane().note.getText()).contains("key-new"), "a message never carries the key");
        assertEquals("key-new", onFx(() -> f.pane().key.getText()), "and the form shows what is saved now");
    }

    @Test void anEmptiedKeyFieldKeepsTheSavedKeyInsteadOfDeletingIt(@TempDir Path dir) throws Exception {
        var f = fixture(dir);
        choose(f, "QX");
        onFx(() -> { f.pane().key.setText(""); f.pane().apply(); return null; });
        assertEquals("key-old", f.manager().load().getById("QX").key());
    }

    @Test void aKeyWithSpacesIsRefusedWithAMessageAndNothingIsWritten(@TempDir Path dir) throws Exception {
        var f = fixture(dir);
        choose(f, "QX");
        onFx(() -> { f.pane().key.setText("two words"); f.pane().apply(); return null; });
        assertTrue(onFx(() -> f.pane().note.getText()).contains("session key"));
        assertFalse(onFx(() -> f.pane().note.getText()).contains("two words"));
        assertEquals("key-old", f.manager().load().getById("QX").key());
    }
}
