package ccsuite.ccacc;

import module java.base;
import ccsuite.webproxy.TokenFeatures;

/**
 * Turns {@link TokenFeatures} into a token estimate (PRP 85): a linear model, tokens = sum of weight x feature, fitted by ridge regression on the RELATIVE error
 * (each sample weighs 1/tokens squared, so a long conversation does not drown a short one). A few feature sets are compared; the plain ratio (characters over one
 * constant) is the baseline. Fitting and checking are offline: the real token counts were collected once, into the sample log.
 */
final class TokenModel {
    /** The feature sets under test: name to feature names. */
    static final Map<String, List<String>> SETS = new LinkedHashMap<>();
    static {
        var cls = TokenFeatures.CLASSES;
        SETS.put("one ratio (fitted)", List.of("chars"));
        SETS.put("block classes", Stream.concat(cls.stream().map(c -> c + ".chars"), Stream.of("messages")).toList());
        SETS.put("character kinds", List.of("wordchars", "words", "digits", "digitruns", "punctchars", "punctruns", "ws", "nl", "upper", "nonascii", "messages"));
        SETS.put("kinds + scripts", Stream.concat(Stream.of("wordchars", "words", "digits", "digitruns", "punctchars", "punctruns", "ws", "nl", "upper", "messages"), TokenFeatures.SCRIPTS.stream()).toList());
        SETS.put("kinds + structure", Stream.concat(SETS.get("character kinds").stream(), Stream.of("b.tool_use", "b.tool_result")).toList());
        SETS.put("block classes + kinds", Stream.concat(Stream.concat(cls.stream().flatMap(c -> Stream.of(c + ".chars", c + ".words", c + ".digits", c + ".punctruns", c + ".ws")), Stream.of("messages", "nonascii", "upper")), Stream.<String>empty()).toList());
    }

    /** Ridge weights for the rows x (one per sample) and the actual tokens y; columns are scaled by their mean so the penalty means the same for every feature. */
    static double[] fit(double[][] x, double[] y, double lambda) {
        var n = x.length;
        var p = x[0].length;
        var scale = new double[p];
        for (var j = 0; j < p; j++) { for (var i = 0; i < n; i++) scale[j] += Math.abs(x[i][j]); scale[j] = scale[j] == 0 ? 1 : scale[j] / n; }
        var a = new double[p][p + 1];
        for (var i = 0; i < n; i++) {
            var w = 1 / (y[i] * y[i]);
            for (var j = 0; j < p; j++) {
                for (var k = 0; k < p; k++) a[j][k] += w * (x[i][j] / scale[j]) * (x[i][k] / scale[k]);
                a[j][p] += w * (x[i][j] / scale[j]) * y[i];
            }
        }
        for (var j = 0; j < p; j++) a[j][j] += lambda * n * 1e-4 * (a[j][j] / n + 1e-9);
        var s = solve(a);
        for (var j = 0; j < p; j++) s[j] /= scale[j];
        return s;
    }

    /** Gaussian elimination with partial pivoting on an augmented p x (p+1) matrix. */
    static double[] solve(double[][] a) {
        var p = a.length;
        for (var c = 0; c < p; c++) {
            var best = c;
            for (var r = c + 1; r < p; r++) if (Math.abs(a[r][c]) > Math.abs(a[best][c])) best = r;
            var t = a[c]; a[c] = a[best]; a[best] = t;
            if (Math.abs(a[c][c]) < 1e-18) continue;
            for (var r = 0; r < p; r++) {
                if (r == c) continue;
                var k = a[r][c] / a[c][c];
                for (var q = c; q <= p; q++) a[r][q] -= k * a[c][q];
            }
        }
        var out = new double[p];
        for (var c = 0; c < p; c++) out[c] = Math.abs(a[c][c]) < 1e-18 ? 0 : a[c][p] / a[c][c];
        return out;
    }

    static double dot(double[] w, double[] x) { var s = 0.0; for (var j = 0; j < w.length; j++) s += w[j] * x[j]; return s; }

    /** Error of predictions against actuals, as signed fractions of the actual. */
    record Score(String name, int n, double meanAbs, double bias, double within5, double p90, double worst) {
        static Score of(String name, double[] predicted, double[] actual) {
            var e = IntStream.range(0, actual.length).mapToDouble(i -> (predicted[i] - actual[i]) / actual[i]).toArray();
            var abs = Arrays.stream(e).map(Math::abs).sorted().toArray();
            return new Score(name, e.length, Arrays.stream(abs).average().orElse(0), Arrays.stream(e).average().orElse(0), Arrays.stream(abs).filter(v -> v <= 0.05).count() * 1.0 / Math.max(1, e.length), abs.length == 0 ? 0 : abs[(int) Math.min(abs.length - 1, Math.floor(abs.length * 0.9))], abs.length == 0 ? 0 : abs[abs.length - 1]);
        }
        String line() { return "%-26s n %4d  mean error %5.1f%%  bias %+5.1f%%  within 5%% %4.0f%%  90th percentile %5.1f%%  worst %5.1f%%".formatted(name, n, meanAbs * 100, bias * 100, within5 * 100, p90 * 100, worst * 100); }
    }

    /** Cross-validated predictions: samples of one group (one session) are never in the fit that predicts them. */
    static double[] crossValidate(double[][] x, double[] y, String[] group, int folds, double lambda) {
        var names = Arrays.stream(group).distinct().sorted().toList();
        var out = new double[y.length];
        for (var fold = 0; fold < folds; fold++) {
            var f = fold;
            var test = IntStream.range(0, y.length).filter(i -> Math.floorMod(names.indexOf(group[i]), folds) == f).toArray();
            var train = IntStream.range(0, y.length).filter(i -> Math.floorMod(names.indexOf(group[i]), folds) != f).toArray();
            if (test.length == 0 || train.length < x[0].length) continue;
            var w = fit(Arrays.stream(train).mapToObj(i -> x[i]).toArray(double[][]::new), Arrays.stream(train).mapToDouble(i -> y[i]).toArray(), lambda);
            for (var i : test) out[i] = Math.max(1, dot(w, x[i]));
        }
        return out;
    }

    private TokenModel() { }
}
