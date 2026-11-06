package ccsuite.localserver.lanes;

/** The model of a request as far as routing cares: haiku and sonnet have routes, anything else (opus, no model) has none except in PAID mode. */
public enum ModelClass {
    HAIKU, SONNET, OTHER;

    public static ModelClass of(String model) {
        var m = model == null ? "" : model.toLowerCase();
        return m.contains("sonnet") ? SONNET : m.contains("haiku") ? HAIKU : OTHER;
    }
}
