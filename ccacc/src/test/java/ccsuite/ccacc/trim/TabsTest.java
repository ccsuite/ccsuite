package ccsuite.ccacc.trim;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TabsTest {

    static final String LIST = """
        {"ok":true,"windows":[{"index":0,"focused":false,"tabs":[
        {"id":"a","title":"group","separator":true},
        {"id":"b","live":true,"selected":true,"pid":10,"claude":{"status":"idle"}},
        {"id":"c","live":true,"selected":false,"pid":20,"claude":{"status":"busy"}},
        {"id":"d","live":true,"selected":false,"pid":30,"claude":{"status":"idle"}},
        {"id":"e","live":false,"selected":true}]}]}""";

    @Test void selectedAndBusyTabsAreHeld() { assertEquals(Set.of(10L, 20L), Tabs.parse(LIST).hold()); }

    @Test void eachLiveTabKeepsItsSessionName() { assertEquals("one", Tabs.parse(LIST.replace("\"pid\":10,\"claude\":{", "\"pid\":10,\"claude\":{\"name\":\"one\",")).nameFor(Set.of(1L, 10L))); }

    @Test void anAnswerThatIsNotOkOrNotJsonHoldsNothing() {
        assertEquals(Set.of(), Tabs.parse("{\"ok\":false}").hold());
        assertEquals(Set.of(), Tabs.parse("not json").hold());
    }
}
