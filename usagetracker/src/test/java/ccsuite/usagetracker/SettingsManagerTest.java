package ccsuite.usagetracker;

import ccsuite.usagetracker.config.SettingsManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SettingsManagerTest {

    @Test void jsonRoundTripKeepsTheCardOptionsAndThePosition(@TempDir Path dir) throws Exception {
        var m = new SettingsManager(dir.resolve("settings.json"));
        var s = m.load();
        s.ui().taskbar(true).floatingWindow(false).lastScreenLoc("-33,14,50").usageRefreshIntervalSecs(90);
        m.save(s);
        var back = new SettingsManager(dir.resolve("settings.json")).load().ui();
        assertTrue(back.taskbar());
        assertFalse(back.floatingWindow());
        assertEquals("-33,14,50", back.lastScreenLoc());
        assertEquals(90, back.usageRefreshIntervalSecs());
    }

    @Test void aPathEndingInXmlStaysTheOldFormat(@TempDir Path dir) throws Exception {
        var xml = dir.resolve("s.xml");
        Files.writeString(xml, "<CCAPIsUsageTrackerSettings><ui layout=\"horizontal\" lastScreenLoc=\"1,2,3\" taskbar=\"true\"/></CCAPIsUsageTrackerSettings>");
        var s = new SettingsManager(xml).load();
        assertEquals("1,2,3", s.ui().lastScreenLoc());
        assertTrue(s.ui().taskbar());
    }
}
