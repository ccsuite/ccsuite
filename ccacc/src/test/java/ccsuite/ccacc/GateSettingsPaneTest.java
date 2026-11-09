package ccsuite.ccacc;

import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** The gate and relay settings on the Servers tab: the port, the workers and the relay address as a form, the secret only as set or not; a refused value says why and changes nothing. */
class GateSettingsPaneTest {

    static <T> T onFx(java.util.function.Supplier<T> s) throws Exception { return MainWindowTest.onFx(s); }

    static GateSettingsPane pane(Path dir) throws Exception {
        MainWindowTest.toolkit();
        var parts = MainWindowTest.parts(dir, new WatchBridge());
        var p = onFx(() -> new GateSettingsPane(parts, new RelayConfig(dir.resolve("relay"))));
        onFx(() -> { p.fill(); return null; });
        return p;
    }

    @Test void itShowsTheSettingsAsTheyAreAndTheSecretOnlyAsSetOrNot(@TempDir Path dir) throws Exception {
        var p = pane(dir);
        assertEquals("7787", onFx(() -> p.port.getText()));
        assertEquals("4", onFx(() -> p.workers.getText()));
        assertEquals("", onFx(() -> p.url.getText()));
        assertTrue(onFx(() -> p.secretState.getText()).contains("none"));
    }

    @Test void validValuesAreSavedTheAddressInItsOwnFile(@TempDir Path dir) throws Exception {
        var p = pane(dir);
        onFx(() -> { p.port.setText("7800"); p.workers.setText("2"); p.url.setText("https://relay.example.org"); p.apply(); return null; });
        var s = p.parts.store.load();
        assertEquals(7800, s.gatePort());
        assertEquals(2, s.relayWorkers());
        assertEquals("https://relay.example.org", new RelayConfig(dir.resolve("relay")).url().orElseThrow());
        assertTrue(onFx(() -> p.note.getText()).contains("next start"), onFx(() -> p.note.getText()));
    }

    @Test void aRefusedValueSaysWhyAndChangesNothing(@TempDir Path dir) throws Exception {
        var p = pane(dir);
        onFx(() -> { p.port.setText("80"); p.workers.setText("0"); p.url.setText("ftp://nope"); p.apply(); return null; });
        var s = p.parts.store.load();
        assertEquals(7787, s.gatePort());
        assertEquals(4, s.relayWorkers());
        assertTrue(new RelayConfig(dir.resolve("relay")).url().isEmpty());
        var note = onFx(() -> p.note.getText());
        assertTrue(note.contains("port") && note.contains("workers") && note.contains("address"), note);
    }

    @Test void aNewSecretIsMadeAndNeverShown(@TempDir Path dir) throws Exception {
        var p = pane(dir);
        onFx(() -> { p.newSecret(); return null; });
        var secret = new RelayConfig(dir.resolve("relay")).secret().orElseThrow();
        assertTrue(secret.startsWith("ccrs_"));
        assertTrue(onFx(() -> p.secretState.getText()).contains("set"));
        assertFalse(onFx(() -> p.secretState.getText() + p.note.getText()).contains(secret), "the secret is never in the window's text");
    }
}
