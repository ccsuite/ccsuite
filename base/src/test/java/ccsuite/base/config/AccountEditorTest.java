package ccsuite.base.config;

import ccsuite.base.model.CCAPIsCredentials;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AccountEditorTest {

    static CCAPIsCredentials one() {
        var all = new CCAPIsCredentials();
        AccountEditor.add(all, Map.of("id", "a", "name", "A", "email", "a@example.org", "key", "secret"));
        return all;
    }

    @Test void addNeedsIdNameAndEmailAndANewId() {
        var all = one();
        assertThrows(IllegalArgumentException.class, () -> AccountEditor.add(all, Map.of("name", "x", "email", "x@example.org")));
        assertThrows(IllegalArgumentException.class, () -> AccountEditor.add(all, Map.of("id", "b", "email", "b@example.org")));
        assertThrows(IllegalArgumentException.class, () -> AccountEditor.add(all, Map.of("id", "a", "name", "x", "email", "x@example.org")));
        assertEquals(1, all.credentials().size());
    }

    @Test void setChangesTypedPropertiesAndEmptyClearsAnOptionalOne() {
        var all = one();
        AccountEditor.set(all, "a", "tier", "3");
        AccountEditor.set(all, "a", "trackUsage", "on");
        AccountEditor.set(all, "a", "claudecodelimit", "90%");
        var a = all.getById("a");
        assertEquals(3, a.tier());
        assertTrue(a.trackUsage());
        assertEquals("90%", a.claudecodelimit());
        AccountEditor.set(all, "a", "claudecodelimit", "");
        assertEquals("95%", a.claudecodelimit());   // cleared: back to the default
    }

    @Test void badInputSaysWhatWasExpected() {
        var all = one();
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AccountEditor.set(all, "a", "tier", "high")).getMessage().contains("whole number"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AccountEditor.set(all, "a", "ping", "maybe")).getMessage().contains("on or off"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AccountEditor.set(all, "a", "id", "z")).getMessage().contains("unknown property"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AccountEditor.set(all, "zz", "tier", "1")).getMessage().contains("no account"));
    }

    @Test void theLimitIsAPercentWrittenWithOrWithoutTheSign() {
        var all = one();
        AccountEditor.set(all, "a", "claudecodelimit", "90");
        assertEquals("90%", all.getById("a").claudecodelimit());
        AccountEditor.set(all, "a", "claudecodelimit", "92.5%");
        assertEquals("92.5%", all.getById("a").claudecodelimit());
        for (var bad : new String[]{"0", "101", "lots"})
            assertTrue(assertThrows(IllegalArgumentException.class, () -> AccountEditor.set(all, "a", "claudecodelimit", bad)).getMessage().contains("percent"));
    }

    @Test void monitorOnlyIsAnOnOffProperty() {
        var all = one();
        assertFalse(all.getById("a").watchOnly());
        AccountEditor.set(all, "a", "watchOnly", "on");
        assertTrue(all.getById("a").watchOnly());
        assertTrue(AccountEditor.describe(all.getById("a")).contains("monitorOnly=true"));
        AccountEditor.set(all, "a", "watchOnly", "off");
        assertFalse(all.getById("a").watchOnly());
    }

    @Test void removeTakesTheAccountOut() {
        var all = one();
        assertEquals("a", AccountEditor.remove(all, "a").id());
        assertNull(all.getById("a"));
        assertThrows(IllegalArgumentException.class, () -> AccountEditor.remove(all, "a"));
    }

    @Test void describeNeverShowsTheKey() {
        var line = AccountEditor.describe(one().getById("a"));
        assertFalse(line.contains("secret"));
        assertTrue(line.contains("key=set"));
    }
}
