package ccsuite.ccacc;

import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** PRP 86 R1: the one policy that says when a cycle is warned and closed, in input tokens. Neutral numbers only. */
class ZonesTest {

    private final LaneSettings s = new LaneSettings();

    @Test void aFreshFreeAccountHasTheAcceptedNumbers() {
        var z = Zones.free(s, 100);
        assertEquals(200_000, z.warn());
        assertEquals(250_000, z.close());
        assertEquals(340_000, z.hard());
    }

    @Test void aHalfUsedAccountShrinksTheHardLimitInProportionAndTheCloseNeverGoesBelowTheStartCostPlusMargin() {
        var z = Zones.free(s, 50);
        assertEquals(170_000, z.hard(), "half the room, half the hard limit");
        assertEquals(130_000, z.close(), "250,000 scaled would be 125,000: kept at the start cost (100,000) plus the working margin (30,000)");
        assertEquals(104_000, z.warn(), "warn keeps the 200:250 proportion to close");
    }

    @Test void anAccountWithLittleRoomLeftClosesAtItsHardLimit() {
        var z = Zones.free(s, 30);
        assertEquals(102_000, z.hard());
        assertEquals(102_000, z.close(), "the floor cannot lie above the hard limit");
        assertTrue(z.warn() <= z.close());
    }

    @Test void anExhaustedAccountHasNoRoomToWorkAtAll() {
        var z = Zones.free(s, 0);
        assertEquals(0, z.hard());
        assertEquals(0, z.close());
        assertEquals(0, z.warn());
        assertEquals("hard", z.judge(1));
    }

    @Test void theZonesAreOrderedForEveryRoom() {
        for (double room = 0; room <= 100; room += 5) {
            var z = Zones.free(s, room);
            assertTrue(z.warn() <= z.close() && z.close() <= z.hard(), "room " + room + ": " + z);
        }
        assertTrue(Zones.free(s, 100).hard() >= Zones.free(s, 60).hard(), "more room never lowers the limit");
    }

    @Test void aRoomOutsideZeroToHundredIsClamped() {
        assertEquals(Zones.free(s, 100), Zones.free(s, 250));
        assertEquals(Zones.free(s, 0), Zones.free(s, -5));
    }

    @Test void thePaidProfileIsIndependentOfRoom() {
        var z = Zones.paid(s);
        assertEquals(200_000, z.warn());
        assertEquals(300_000, z.close());
        assertEquals(500_000, z.hard());
    }

    @Test void judgeNamesTheZoneOfAnInputSize() {
        var z = Zones.free(s, 100);
        assertEquals("work", z.judge(199_999));
        assertEquals("warn", z.judge(200_000));
        assertEquals("close", z.judge(250_000));
        assertEquals("hard", z.judge(340_000));
    }

    @Test void theLinesAreKeyValueForTheHookAndTheStatusLine() {
        var lines = Zones.free(s, 100).lines("free");
        assertTrue(lines.contains("free.warn=200000") && lines.contains("free.close=250000") && lines.contains("free.hard=340000") && lines.contains("free.room=100"), lines.toString());
    }

    @Test void zonesCommandIsReadOnlyAndAnswersWithoutAnApp(@TempDir Path dir) {
        var c = new LaneCommands(new CcaccSettingsStore(dir.resolve("settings.json")), null, new FreeLedger(dir.resolve("ledger")));
        var all = c.run(List.of("lanes", "zones"));
        assertTrue(all.ok(), all.message());
        assertTrue(all.message().contains("free.close=250000") && all.message().contains("paid.close=300000"), all.message());
        var half = c.run(List.of("lanes", "zones", "--room", "50"));
        assertTrue(half.ok() && half.message().contains("free.hard=170000") && half.message().contains("free.room=50"), half.message());
        assertFalse(c.run(List.of("lanes", "zones", "--room", "lots")).ok());
        assertFalse(Files.exists(dir.resolve("settings.json")), "no settings were written");
    }
}
