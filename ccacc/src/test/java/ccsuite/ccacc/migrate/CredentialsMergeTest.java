package ccsuite.ccacc.migrate;

import ccsuite.base.config.AccountEditor;
import ccsuite.base.model.CCAPIsCredentials;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CredentialsMergeTest {

    static CCAPIsCredentials with(String id, String name, String key) {
        var all = new CCAPIsCredentials();
        AccountEditor.add(all, Map.of("id", id, "name", name, "email", id + "@example.org", "key", key));
        return all;
    }

    @Test void anAccountOnlyTheOldFileHasIsAdded() {
        var json = with("a", "A", "k");
        var xml = with("a", "A", "k");
        AccountEditor.add(xml, Map.of("id", "b", "name", "B", "email", "b@example.org"));
        var notes = CredentialsMerge.merge(json, xml);
        assertEquals(java.util.List.of("added account b from the old file"), notes);
        assertNotNull(json.getById("b"));
    }

    @Test void whereBothHaveAnAccountTheJsonWinsAndTheDifferenceIsReportedWithoutTheKey() {
        var json = with("a", "Mine", "secret-json");
        var xml = with("a", "Theirs", "secret-xml");
        var notes = CredentialsMerge.merge(json, xml);
        assertEquals("Mine", json.getById("a").name());
        assertTrue(notes.stream().anyMatch(n -> n.contains("a.name differs") && n.contains("Mine") && n.contains("Theirs")));
        assertTrue(notes.stream().anyMatch(n -> n.contains("a.key differs")));
        assertTrue(notes.stream().noneMatch(n -> n.contains("secret")));
    }

    @Test void identicalFilesSayNothing() {
        assertTrue(CredentialsMerge.merge(with("a", "A", "k"), with("a", "A", "k")).isEmpty());
    }
}
