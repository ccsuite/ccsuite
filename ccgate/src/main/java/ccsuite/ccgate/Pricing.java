package ccsuite.ccgate;

import module java.base;
import com.fasterxml.jackson.databind.*;

/**
 * Prices a call from its tokens: list-price dollars per token kind, then quota points by dividing each kind's dollars
 * by the measured dollars-per-point of the model's family. The tables are in pricing.json beside this class.
 */
public final class Pricing {

    static final double MILLION = 1_000_000d, WRITE_5M = 1.25, WRITE_1H = 2.0;

    final JsonNode perPoint;
    final List<JsonNode> models = new ArrayList<>();

    public Pricing(JsonNode table) {
        perPoint = table.path("dollarsPerPoint");
        table.path("models").forEach(models::add);
    }

    public static Pricing standard() {
        try (var in = Pricing.class.getResourceAsStream("pricing.json")) {
            return new Pricing(new ObjectMapper().readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The family a model id belongs to (haiku, sonnet, opus, fable), or "unknown". */
    public String family(String model) {
        var m = entry(model);
        return m == null ? "unknown" : m.path("family").asText();
    }

    public Cost cost(String model, Usage u) {
        var m = entry(model);
        var family = m == null ? "unknown" : m.path("family").asText();
        var price = m == null ? dearest() : m;
        var pp = m == null ? cheapestPoint() : perPoint.path(family);
        var writes5m = u.cacheCreation5m() + u.cacheCreation1h() == 0 ? u.cacheCreationInputTokens() : u.cacheCreation5m();
        var in = (u.inputTokens() + writes5m * WRITE_5M + u.cacheCreation1h() * WRITE_1H) * rate(price, "input");
        var out = u.outputTokens() * rate(price, "output");
        var read = u.cacheReadInputTokens() * rate(price, "cacheRead");
        var points = in / pp.path("input").asDouble() + out / pp.path("output").asDouble() + read / pp.path("cacheRead").asDouble();
        return new Cost().family(family).points(points).dollars(in + out + read);
    }

    static double rate(JsonNode price, String kind) {
        return price.path(kind).asDouble() / MILLION;
    }

    JsonNode entry(String model) {
        if (model == null) return null;
        var id = model.toLowerCase(Locale.ROOT);
        return models.stream().filter(m -> id.contains(m.path("match").asText())).findFirst().orElse(null);
    }

    JsonNode dearest() {
        return models.stream().max(Comparator.comparingDouble(m -> m.path("output").asDouble())).orElseThrow();
    }

    JsonNode cheapestPoint() {
        var best = (JsonNode) null;
        for (var it = perPoint.elements(); it.hasNext(); ) {
            var p = it.next();
            if (best == null || p.path("input").asDouble() < best.path("input").asDouble()) best = p;
        }
        return best;
    }
}
