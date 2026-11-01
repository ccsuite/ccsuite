package ccsuite.ccgate;

import module java.base;

/** "--name value" pairs and plain words from a command line, plus the "a=1,b=2" maps the budgets use. */
public final class Flags {

    final Map<String, String> named = new LinkedHashMap<>();
    final List<String> words = new ArrayList<>();

    public Flags(List<String> args) {
        for (var i = 0; i < args.size(); i++) {
            var a = args.get(i);
            if (a.startsWith("--")) {
                var hasValue = i + 1 < args.size() && !args.get(i + 1).startsWith("--");
                named.put(a.substring(2), hasValue ? args.get(++i) : "true");
            } else words.add(a);
        }
    }

    public String get(String name, String fallback) {
        return named.getOrDefault(name, fallback);
    }

    public boolean has(String name) {
        return named.containsKey(name);
    }

    public String word(int i) {
        if (i >= words.size()) throw new IllegalArgumentException("missing argument " + (i + 1));
        return words.get(i);
    }

    public static Map<String, Double> points(String s) {
        var m = new LinkedHashMap<String, Double>();
        if (s == null || s.isBlank()) return m;
        for (var part : s.split(",")) {
            var kv = part.split("=", 2);
            if (kv.length != 2) throw new IllegalArgumentException("expected family=points, got '" + part + "'");
            m.put(kv[0].strip(), Double.parseDouble(kv[1].strip()));
        }
        return m;
    }

    public static List<String> list(String s) {
        return s == null || s.isBlank() ? new ArrayList<>() : new ArrayList<>(Arrays.stream(s.split(",")).map(String::strip).toList());
    }
}
