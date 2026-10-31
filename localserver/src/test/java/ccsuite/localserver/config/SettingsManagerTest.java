package ccsuite.localserver.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SettingsManagerTest {

    @Test void aFreshFileGetsTheDefaultsWrittenIn(@TempDir Path dir) throws Exception {
        var file = dir.resolve("settings.json");
        var s = new SettingsManager(file).load();
        assertEquals(8790, s.port());
        assertTrue(Files.readString(file).contains("8790"));
    }

    @Test void thePortSurvivesAReload(@TempDir Path dir) throws Exception {
        var m = new SettingsManager(dir.resolve("settings.json"));
        m.save(m.load().port(9100));
        assertEquals(9100, new SettingsManager(dir.resolve("settings.json")).load().port());
    }

    @Test void aPathEndingInXmlStaysTheOldFormat(@TempDir Path dir) throws Exception {
        var xml = dir.resolve("s.xml");
        Files.writeString(xml, "<CCAPIsLocalServerSettings port=\"8123\"/>");
        assertEquals(8123, new SettingsManager(xml).load().port());
    }
}
