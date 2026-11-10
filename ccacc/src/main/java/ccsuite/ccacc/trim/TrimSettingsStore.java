package ccsuite.ccacc.trim;

import module java.base;

/** {@code ~/ccsuite/ccacc/trim-settings.json}. */
final class TrimSettingsStore {

    static Path file() { return TrimJson.home().resolve("trim-settings.json"); }

    static TrimSettings load() { return TrimJson.load(file(), TrimSettings.class, TrimSettings::new); }

    static void save(TrimSettings s) { TrimJson.save(file(), s); }

    /** {@code set <name> <value>}: one setting by its name; throws IllegalArgumentException for an unknown name or a value that does not fit. */
    static TrimSettings set(TrimSettings s, String name, String value) {
        try {
            return switch (name) {
                case "idleMinutes" -> s.idleMinutes(positive(Integer.parseInt(value)));
                case "minWorkingSetMb" -> s.minWorkingSetMb(positive(Integer.parseInt(value)));
                case "minGapMinutes" -> s.minGapMinutes(positive(Integer.parseInt(value)));
                case "checkSeconds" -> s.checkSeconds(positive(Integer.parseInt(value)));
                case "onlyBelowFreeGb" -> s.onlyBelowFreeGb(Double.parseDouble(value));
                case "busyCpuPercent" -> s.busyCpuPercent(Double.parseDouble(value));
                default -> throw new IllegalArgumentException("unknown setting " + name + " (idleMinutes, minWorkingSetMb, minGapMinutes, checkSeconds, onlyBelowFreeGb, busyCpuPercent)");
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " takes a number, not " + value);
        }
    }

    private static int positive(int v) {
        if (v < 1) throw new IllegalArgumentException("must be at least 1");
        return v;
    }

    private TrimSettingsStore() {}
}
