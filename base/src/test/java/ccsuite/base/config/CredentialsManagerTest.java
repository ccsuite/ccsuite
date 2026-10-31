package ccsuite.base.config;

import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.CCAPIsCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CredentialsManagerTest {

    static final String XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <CCAPIsCredentials>
              <!-- a hand-written comment, not kept by the JSON file -->
              <CCAPICredential id="one" name="First" email="one@example.org" ccapiBaseUrl="claude.example" orgId="org-1" key="k1" trackUsage="true" tier="2" claudecodelimit="95%"/>
              <CCAPICredential id="two" name="Second" email="two@example.org" ccapiBaseUrl="claude.example" orgId="org-2" key="k2" active="false"/>
            </CCAPIsCredentials>
            """;

    static CCAPIsCredentials sample() {
        var all = new CCAPIsCredentials();
        all.credentials().add(new CCAPICredential().id("a").name("A").email("a@example.org").ccapiBaseUrl("claude.example").orgId("o").key("k").trackUsage(true).claudecodeactive(true));
        all.credentials().add(new CCAPICredential().id("b").name("B").email("b@example.org").ccapiBaseUrl("claude.example").orgId("o2").key("k2"));
        return all;
    }

    @Test void jsonRoundTripKeepsEveryField(@TempDir Path dir) throws Exception {
        var m = new CredentialsManager(dir.resolve("credentials.json"));
        m.save(sample());
        var back = m.load();
        assertEquals(2, back.credentials().size());
        var a = back.getById("a");
        assertEquals("a@example.org", a.email());
        assertTrue(a.trackUsage() && a.claudecodeactive());
        assertFalse(back.getById("b").trackUsage());
        assertEquals(1, back.getById("b").tier());
        assertFalse(m.importedFromLegacy());
    }

    @Test void unsetOptionalFieldsAreLeftOutOfTheFile(@TempDir Path dir) throws Exception {
        var file = dir.resolve("credentials.json");
        new CredentialsManager(file).save(sample());
        var text = Files.readString(file);
        assertFalse(text.contains("null"));
        assertFalse(text.contains("\"ua\""));
        assertTrue(text.contains("\"id\" : \"a\""));
    }

    @Test void theFirstLoadImportsTheOldXmlAndLeavesItAlone(@TempDir Path dir) throws Exception {
        var xml = dir.resolve("old/CCAPIsCredentials.xml");
        Files.createDirectories(xml.getParent());
        Files.writeString(xml, XML);
        var json = dir.resolve("new/credentials.json");
        var m = new CredentialsManager(json, xml);
        var loaded = m.load();
        assertTrue(m.importedFromLegacy());
        assertTrue(Files.exists(json));
        assertEquals(XML, Files.readString(xml));
        assertEquals("95%", loaded.getById("one").claudecodelimit());
        assertFalse(loaded.getById("two").active());
        var second = new CredentialsManager(json, xml);
        assertEquals(2, second.load().credentials().size());
        assertFalse(second.importedFromLegacy());
    }

    @Test void onceTheJsonExistsLaterXmlChangesAreNotRead(@TempDir Path dir) throws Exception {
        var xml = dir.resolve("CCAPIsCredentials.xml");
        Files.writeString(xml, XML);
        var m = new CredentialsManager(dir.resolve("credentials.json"), xml);
        m.load();
        Files.writeString(xml, XML.replace("id=\"two\"", "id=\"changed\""));
        assertNotNull(new CredentialsManager(dir.resolve("credentials.json"), xml).load().getById("two"));
    }

    @Test void aPathEndingInXmlStaysTheOldFormat(@TempDir Path dir) throws Exception {
        var xml = dir.resolve("custom.xml");
        Files.writeString(xml, XML);
        var m = new CredentialsManager(xml);
        var all = m.load();
        all.getById("one").name("Renamed");
        m.save(all);
        assertTrue(Files.readString(xml).contains("Renamed"));
        assertTrue(Files.readString(xml).contains("<CCAPICredential"));
    }

    @Test void missingFileSaysHowToCreateIt(@TempDir Path dir) {
        var e = assertThrows(java.io.IOException.class, () -> new CredentialsManager(dir.resolve("none.json")).load());
        assertTrue(e.getMessage().contains("not found"));
    }

    @Test void markingTheActiveAccountInJsonFlipsTheOthers(@TempDir Path dir) throws Exception {
        var file = dir.resolve("credentials.json");
        var m = new CredentialsManager(file);
        m.save(sample());
        assertTrue(CredentialsActiveFlagWriter.setActive(file, "b"));
        var back = m.load();
        assertTrue(back.getById("b").claudecodeactive());
        assertFalse(back.getById("a").claudecodeactive());
        assertFalse(CredentialsActiveFlagWriter.setActive(file, "b"));
    }
}
