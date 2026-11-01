package ccsuite.quotaweights;

import module java.base;
import com.fasterxml.jackson.databind.*;

/**
 * Weighs tokens from quotaweights.json beside this class: list-price dollars per token kind, then quota points by dividing
 * each kind's dollars by the measured dollars-per-point of the model's family. Cache writes the caller could not split
 * into 5-minute and 1-hour go in as write5m.
 */
public final class QuotaWeights {

    public static final String INPUT = "input", OUTPUT = "output", CACHE_READ = "cacheRead";
    static final double MILLION = 1_000_000d, WRITE_5M = 1.25, WRITE_1H = 2.0;

    final JsonNode perPoint;
    final List<JsonNode> models = new ArrayList<>();

    public QuotaWeights(JsonNode table) {
        perPoint = table.path("dollarsPerPoint");
        table.path("models").forEach(models::add);
    }

    public static QuotaWeights standard() {
        try (var in = QuotaWeights.class.getResourceAsStream("quotaweights.json")) {
            return new QuotaWeights(new ObjectMapper().readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The family a model id belongs to (haiku, sonnet, opus, fable), or "unknown". */
    public String family(String model) {
        var m = entry(model);
        return m == null ? "unknown" : m.path("family").asText();
    }

    public Cost cost(String model, long input, long output, long cacheRead, long write5m, long write1h) {
        var m = entry(model);
        var price = m == null ? dearest() : m;
        var pp = pointsOf(m);
        var pIn = input * rate(price, INPUT) / pp.path(INPUT).asDouble();
        var pOut = output * rate(price, OUTPUT) / pp.path(OUTPUT).asDouble();
        var pRead = cacheRead * rate(price, CACHE_READ) / pp.path(CACHE_READ).asDouble();
        var pWrite = (write5m * WRITE_5M + write1h * WRITE_1H) * rate(price, INPUT) / pp.path(INPUT).asDouble();
        var points = pIn + pOut + pRead + pWrite;
        var assumed = pWrite + (isAssumed(m, pp, INPUT) ? pIn : 0) + (isAssumed(m, pp, OUTPUT) ? pOut : 0) + (isAssumed(m, pp, CACHE_READ) ? pRead : 0);
        var dollars = (input + write5m * WRITE_5M + write1h * WRITE_1H) * rate(price, INPUT) + output * rate(price, OUTPUT) + cacheRead * rate(price, CACHE_READ);
        return new Cost(family(model), points, assumed, dollars);
    }

    /** Tokens of one kind (INPUT, OUTPUT or CACHE_READ) that move one account's 5-hour window by 1%. */
    public double tokensPerPoint(String model, String kind) {
        var m = entry(model);
        return pointsOf(m).path(kind).asDouble() / rate(m == null ? dearest() : m, kind);
    }

    static double rate(JsonNode price, String kind) {
        return price.path(kind).asDouble() / MILLION;
    }

    static boolean isAssumed(JsonNode m, JsonNode pp, String kind) {
        if (m == null) return true;
        for (var a : pp.path("assumed")) if (a.asText().equals(kind)) return true;
        return false;
    }

    JsonNode pointsOf(JsonNode m) {
        return m == null ? cheapestPoint() : perPoint.path(m.path("family").asText());
    }

    JsonNode entry(String model) {
        if (model == null) return null;
        var id = model.toLowerCase(Locale.ROOT);
        return models.stream().filter(m -> id.contains(m.path("match").asText())).findFirst().orElse(null);
    }

    JsonNode dearest() {
        return models.stream().max(Comparator.comparingDouble(m -> m.path(OUTPUT).asDouble())).orElseThrow();
    }

    JsonNode cheapestPoint() {
        var best = (JsonNode) null;
        for (var it = perPoint.elements(); it.hasNext(); ) {
            var p = it.next();
            if (best == null || p.path(INPUT).asDouble() < best.path(INPUT).asDouble()) best = p;
        }
        return best;
    }
}
