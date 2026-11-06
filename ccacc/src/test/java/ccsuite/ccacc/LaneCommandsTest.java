package ccsuite.ccacc;

import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** {@code ccacc lanes} with no app running: it changes the settings on disk. */
class LaneCommandsTest {

    @Test void onlyLaneCommandsAreHandledAndTheWatchersModeIsLeftAlone() {
        assertTrue(LaneCommands.handles(List.of("lanes")));
        assertFalse(LaneCommands.handles(List.of("mode", "auto")));
        assertFalse(LaneCommands.handles(List.of("mode", "fclaude", "balanced")), "there are no lane modes any more: each port is one lane");
        assertFalse(LaneCommands.handles(List.of("part", "server", "on")));
    }

    @Test void theDefaultsAreTheTwoPortsOffUntilSwitchedOn() {
        var l = new LaneSettings();
        assertEquals(List.of(8681, 8686), LaneSettings.NAMES.stream().map(l::port).toList());
        assertEquals(List.of("pclaude", "fclaude"), l.specs().stream().map(ccsuite.localserver.lanes.PortSpec::name).toList());
        assertEquals(List.of(ccsuite.localserver.lanes.LaneKind.PAID, ccsuite.localserver.lanes.LaneKind.FREE), l.specs().stream().map(ccsuite.localserver.lanes.PortSpec::kind).toList());
        assertFalse(l.enabled());
        assertEquals(2, l.perAccountSlots());
    }

    @Test void badInputIsAnErrorAndChangesNothing(@TempDir Path dir) {
        var store = new CcaccSettingsStore(dir.resolve("settings.json"));
        var c = new LaneCommands(store, null);
        assertFalse(c.run(List.of("lanes", "set", "freeFiveLimit", "lots")).ok());
        assertFalse(c.run(List.of("lanes", "set", "nosuch", "1")).ok());
        assertFalse(c.run(List.of("lanes", "set", "paidAllowsSonnet", "true")).ok(), "that setting is gone with the hybrid");
        assertFalse(c.run(List.of("lanes", "mode", "fclaude", "free")).ok());
        assertFalse(Files.exists(dir.resolve("settings.json")));
    }

    @Test void theSettingsAreSetByName(@TempDir Path dir) {
        var store = new CcaccSettingsStore(dir.resolve("settings.json"));
        var c = new LaneCommands(store, null);
        assertTrue(c.run(List.of("lanes", "set", "freeFiveLimit", "0.9")).ok());
        assertTrue(c.run(List.of("lanes", "set", "perAccountSlots", "3")).ok());
        assertTrue(c.run(List.of("lanes", "free-accounts", "F2,F1")).ok());
        assertTrue(c.run(List.of("lanes", "on")).ok());
        var l = store.load().lanes();
        assertEquals(0.9, l.freeFiveLimit());
        assertEquals(3, l.perAccountSlots());
        assertEquals(List.of("F2", "F1"), l.freeAccounts());
        assertTrue(l.enabled());
    }

    @Test void anOldSettingsFileWithHybridFieldsStillLoads(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"), "{\"watch\":true,\"lanes\":{\"enabled\":true,\"pclaudePort\":8681,\"fclaudePort\":8686,\"pfclaudePort\":8687,\"fclaudeMode\":\"BALANCED\",\"paidAllowsSonnet\":false,\"paidPaceLimit\":100.0}}");
        var l = new CcaccSettingsStore(dir.resolve("settings.json")).load().lanes();
        assertTrue(l.enabled());
        assertEquals(8686, l.fclaudePort());
    }

    @Test void anOldSettingsFileWithoutLanesLoadsWithTheDefaults(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"), "{\"watch\":true,\"widget\":false,\"server\":false,\"gate\":true,\"gatePort\":7787}");
        var l = new CcaccSettingsStore(dir.resolve("settings.json")).load().lanes();
        assertFalse(l.enabled());
        assertEquals(8686, l.fclaudePort());
    }

    @Test void showListsTheTwoPorts(@TempDir Path dir) {
        var reply = new LaneCommands(new CcaccSettingsStore(dir.resolve("settings.json")), null).run(List.of("lanes"));
        assertTrue(reply.ok());
        assertTrue(reply.message().contains("pclaude") && reply.message().contains("8681") && reply.message().contains("fclaude") && reply.message().contains("8686"), reply.message());
        assertFalse(reply.message().contains("8687"));
    }
}
