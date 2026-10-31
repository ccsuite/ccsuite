package ccsuite.ccacc;

import ccsuite.ccacc.Control.Reply;
import ccsuite.oauthswap.watch.WatchMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ControlTest {

    @Test void aCommandReachesTheAppAndTheAnswerComesBack(@TempDir Path dir) throws Exception {
        var seen = new ArrayList<List<String>>();
        var file = dir.resolve("control.json");
        try (var server = ControlServer.start(file, args -> { seen.add(args); return Reply.ok("done " + args.get(0)); })) {
            var reply = ControlClient.send(file, List.of("switch", "BOB"));
            assertTrue(reply.isPresent());
            assertTrue(reply.get().ok());
            assertEquals("done switch", reply.get().message());
            assertEquals(List.of(List.of("switch", "BOB")), seen);
        }
    }

    @Test void anErrorFromTheAppIsAnErrorAtTheCommandLine(@TempDir Path dir) throws Exception {
        var file = dir.resolve("control.json");
        try (var server = ControlServer.start(file, args -> Reply.error("no such account"))) {
            var reply = ControlClient.send(file, List.of("switch", "X")).orElseThrow();
            assertFalse(reply.ok());
            assertEquals("no such account", reply.message());
        }
    }

    @Test void aWrongTokenIsRefusedAndNothingRuns(@TempDir Path dir) throws Exception {
        var ran = new ArrayList<List<String>>();
        var file = dir.resolve("control.json");
        try (var server = ControlServer.start(file, args -> { ran.add(args); return Reply.ok(""); })) {
            var real = Control.JSON.readValue(file.toFile(), Control.Endpoint.class);
            Control.JSON.writeValue(file.toFile(), new Control.Endpoint(real.port(), "not-the-token", 1));
            var reply = ControlClient.send(file, List.of("switch", "BOB")).orElseThrow();
            assertFalse(reply.ok());
            assertTrue(ran.isEmpty());
        }
    }

    @Test void noFileOrNobodyListeningMeansNoApp(@TempDir Path dir) throws Exception {
        var file = dir.resolve("control.json");
        assertTrue(ControlClient.send(file, List.of("mode", "locked")).isEmpty());
        // a file left behind by an app that is gone: the port answers nobody
        Control.JSON.writeValue(file.toFile(), new Control.Endpoint(1, "x", 1));
        assertTrue(ControlClient.send(file, List.of("mode", "locked")).isEmpty());
    }

    @Test void closingTheServerRemovesItsFile(@TempDir Path dir) throws Exception {
        var file = dir.resolve("control.json");
        var server = ControlServer.start(file, args -> Reply.ok(""));
        assertTrue(Files.exists(file));
        server.close();
        assertFalse(Files.exists(file));
    }

    @Test void onlyTheCommandsThatChangeStateGoToTheApp() {
        assertTrue(Control.changesState(new String[]{"switch", "BOB"}));
        assertTrue(Control.changesState(new String[]{"account", "set", "a", "limit=90"}));
        assertFalse(Control.changesState(new String[]{"list"}));
        assertFalse(Control.changesState(new String[]{"status"}));
        assertFalse(Control.changesState(new String[]{}));
    }

    // ---- the commands run alone (no app): settings and accounts are changed on disk

    Commands alone(Path dir) {
        var f = AccountsTest.fixture(dir);
        return new Commands(f.accounts(), new CcaccSettingsStore(dir.resolve("settings.json")), null, null);
    }

    @Test void modeAloneIsSavedForTheNextStart(@TempDir Path dir) {
        var c = alone(dir);
        var r = c.run(List.of("mode", "locked"));
        assertTrue(r.ok(), r.message());
        assertEquals(WatchMode.LOCKED, new CcaccSettingsStore(dir.resolve("settings.json")).load().watchMode());
        assertFalse(c.run(List.of("mode", "sideways")).ok());
    }

    @Test void aPartAloneIsSavedForTheNextStart(@TempDir Path dir) {
        var c = alone(dir);
        assertTrue(c.run(List.of("part", "server", "off")).ok());
        assertFalse(new CcaccSettingsStore(dir.resolve("settings.json")).load().server());
        assertFalse(c.run(List.of("part", "printer", "on")).ok());
        assertFalse(c.run(List.of("part", "server", "maybe")).ok());
    }

    @Test void accountsCanBeAddedChangedListedAndRemoved(@TempDir Path dir) {
        var c = alone(dir);
        assertTrue(c.run(List.of("account", "add", "bob", "name=G", "email=g@example.org", "trackUsage=on")).ok());
        assertTrue(c.run(List.of("account", "set", "bob", "claudecodelimit=90", "watchOnly=on")).ok());
        var list = c.run(List.of("account", "list"));
        assertTrue(list.message().contains("bob") && list.message().contains("monitorOnly=true") && list.message().contains("limit=90%"), list.message());
        assertFalse(c.run(List.of("account", "set", "bob", "claudecodelimit=150")).ok());
        assertTrue(c.run(List.of("account", "remove", "bob")).ok());
        assertFalse(c.run(List.of("account", "remove", "bob")).ok());
    }

    @Test void switchNeedsTheRunningApp(@TempDir Path dir) {
        var r = alone(dir).run(List.of("switch", "bob"));
        assertFalse(r.ok());
    }
}
