package ccsuite.ccacc;

import module java.base;
import ccsuite.webproxy.TokenFeatures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** PRP 85, the block-type model: the classifier, the features and the fit, all offline with made-up data (no count API, no real text). */
class TokenModelTest {
    static final ObjectMapper JSON = new ObjectMapper();

    @Test void aBlockIsClassifiedFromItsOwnText() {
        assertEquals("json", TokenFeatures.classOf("{\"a\": [1, 2, 3]}", false));
        assertEquals("json", TokenFeatures.classOf("anything at all", true), "a tool call's input is json");
        assertEquals("numbered", TokenFeatures.classOf("     1\tpackage x;\n     2\t\n     3\tclass A { }", false));
        assertEquals("code", TokenFeatures.classOf("int f(int a) { return (a + 1) * g(a, \"x\"); }\nif (a > 0) { b = c[0]; }", false));
        assertEquals("prose", TokenFeatures.classOf("A few plain words about a thing, written the way people write them.", false));
    }

    @Test void theFeaturesCountCharactersByClassAndByKind() throws Exception {
        var messages = JSON.readTree("[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"Hello world 42\"}]},"
            + "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}]}]");
        var f = TokenFeatures.of(messages);
        assertEquals(2, f.get("messages"));
        assertEquals(2, f.get("blocks"));
        assertEquals("Hello world 42".length() + "Bash".length(), f.get("prose.chars"), "the text and the tool name");
        assertEquals("{\"command\":\"ls\"}".length(), f.get("json.chars"));
        assertEquals(2, f.get("digits"), "the digits of 42");
        assertEquals(1, f.get("digitruns"));
        assertTrue(f.get("words") >= 3);
    }

    @Test void aNonAsciiCharacterIsPlacedInItsScriptGroup() {
        assertEquals("latinx", TokenFeatures.script('é'));
        assertEquals("indic", TokenFeatures.script('अ'));
        assertEquals("cyrillic", TokenFeatures.script('ж'));
        assertEquals("symbols", TokenFeatures.script('→'));
        assertEquals("cjk", TokenFeatures.script('日'));
    }

    @Test void theFitRecoversKnownWeightsFromNoiselessData() {
        var rnd = new Random(1);
        var x = new double[60][3];
        var y = new double[60];
        for (var i = 0; i < 60; i++) {
            x[i] = new double[]{100 + rnd.nextInt(5000), 10 + rnd.nextInt(800), rnd.nextInt(60)};
            y[i] = 0.30 * x[i][0] + 1.2 * x[i][1] + 5 * x[i][2] + 20;
        }
        var w = TokenModel.fit(x, y, 1);
        assertEquals(0.30, w[0], 0.03);
        assertEquals(1.2, w[1], 0.1);
        for (var i = 0; i < 60; i += 7) assertEquals(y[i], TokenModel.dot(w, x[i]), y[i] * 0.02);
    }

    @Test void crossValidationNeverUsesAGroupToPredictItself() {
        var rnd = new Random(2);
        var n = 80;
        var x = new double[n][1];
        var y = new double[n];
        var group = new String[n];
        for (var i = 0; i < n; i++) { x[i][0] = 100 + rnd.nextInt(1000); y[i] = x[i][0] * (i % 2 == 0 ? 0.4 : 0.4); group[i] = "g" + (i % 10); }
        var predicted = TokenModel.crossValidate(x, y, group, 5, 1);
        var score = TokenModel.Score.of("t", predicted, y);
        assertTrue(score.meanAbs() < 0.02, score.line());
        assertEquals(1.0, score.within5(), 0.0);
    }

    @Test void aScoreSaysHowManyAreWithinFivePercentAndWhichWay() {
        var s = TokenModel.Score.of("t", new double[]{104, 120, 90}, new double[]{100, 100, 100});
        assertEquals(1.0 / 3, s.within5(), 1e-9);
        assertEquals((0.04 + 0.20 - 0.10) / 3, s.bias(), 1e-9);
        assertEquals((0.04 + 0.20 + 0.10) / 3, s.meanAbs(), 1e-9);
    }

    @Test void aChunkFromTheMiddleOfAToolLoopKeepsItsOrphanedResultsAsText() throws Exception {
        var in = JSON.readTree("[{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"gone\",\"content\":\"output of a call made before the cut\"}]},"
            + "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"Bash\",\"input\":{}}]},"
            + "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"a\",\"content\":\"ok\"}]}]");
        var list = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        in.forEach(list::add);
        assertTrue(TokenCheckTexts.paired(list).isEmpty(), "dropped, the whole stretch used to vanish");
        var kept = TokenCheckTexts.paired(list, true);
        assertEquals(3, kept.size());
        assertEquals("text", kept.get(0).path("content").get(0).path("type").asText());
        assertEquals("output of a call made before the cut", kept.get(0).path("content").get(0).path("text").asText());
        assertEquals("tool_result", kept.get(2).path("content").get(0).path("type").asText(), "a result whose call is in the chunk stays a result");
    }

    @Test void aPrunedSessionStillCountsFromItsSavedFeatures(@TempDir Path dir) throws Exception {
        var projects = Files.createDirectories(dir.resolve("projects"));   // no session file in it: the transcript was pruned
        var store = Files.createDirectories(dir.resolve("store"));
        Files.writeString(store.resolve("samples.jsonl"), "{\"file\":\"p/gone.jsonl\",\"start\":0,\"end\":4,\"net\":1234,\"month\":\"2026-10\",\"set\":\"whole\",\"v\":2}\n");
        assertTrue(TokenModelCli.load(store.resolve("samples.jsonl"), projects).isEmpty(), "without a snapshot a pruned session is lost");
        Files.writeString(TokenSnapshot.file(store), "{\"key\":\"p/gone.jsonl 0-4\",\"file\":\"p/gone.jsonl\",\"month\":\"2026-10\",\"set\":\"whole\",\"net\":1234.0,\"promptChars\":5000.0,\"f\":{\"chars\":4800.0,\"words\":700.0}}\n");
        var s = TokenModelCli.load(store.resolve("samples.jsonl"), projects);
        assertEquals(1, s.size());
        assertEquals(1234, s.get(0).net());
        assertEquals(4800, s.get(0).f().get("chars"));
        assertEquals("whole", s.get(0).set());
    }
}
