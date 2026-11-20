package ccsuite.taskbarslot;

import module java.base;

/**
 * The contract between the tools and the slot DLL. The cards registered by tools are the subkeys of {@code <cardsKey>\<id>}, each with SZ {@code title} (the title of the
 * window to host) and DWORD {@code order}. The band is {@code legacy} + {@code registered} cards long, where {@code legacy} is ccacc's DWORD {@code slotCards} (1 or 2, default 1).
 * ccacc's cards keep slots 0..legacy-1, registered cards follow, by order then id. The DLL reads the same two things when the band is created.
 */
public final class Cards {
    public static final String CARDS_KEY = "HKCU\\Software\\ccsuite\\taskbar\\cards", LEGACY_KEY = "HKCU\\Software\\ccsuite\\ccacc", LEGACY_VALUE = "slotCards";
    public static final int MAX = 16;
    static final long MAX_AGE_MS = 10_000;
    static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}"), VALUE = Pattern.compile("^\\s+(\\S+)\\s+(REG_\\w+)\\s*(.*?)\\s*$");

    final String cardsKey, legacyKey;
    volatile List<Card> cache = List.of();
    volatile long readAt;

    public Cards(String cardsKey, String legacyKey) { this.cardsKey = cardsKey; this.legacyKey = legacyKey; }

    /** The real keys. */
    public static Cards live() { return new Cards(CARDS_KEY, LEGACY_KEY); }

    /** Registers (or changes) a card. Takes effect when Explorer next creates the band: toggle it in the taskbar's Toolbars menu, or restart Explorer. */
    public boolean register(String id, String title, int order) {
        if (!ID.matcher(id).matches() || title.isBlank() || title.contains("\"") || order < 0) throw new IllegalArgumentException("bad card: " + id + " / " + title + " / " + order);
        var key = cardsKey + "\\" + id;
        var ok = Reg.add(key, "title", "REG_SZ", title) && Reg.add(key, "order", "REG_DWORD", Integer.toString(order));
        readAt = 0;
        return ok;
    }

    public boolean unregister(String id) {
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("bad card id: " + id);
        var ok = Reg.delete(cardsKey + "\\" + id);
        readAt = 0;
        return ok;
    }

    /** The registered cards in band order, read again at most every ten seconds (the layout asks every second, and each read is a process). */
    public List<Card> registered() {
        if (System.currentTimeMillis() - readAt > MAX_AGE_MS) { cache = registeredNow(); readAt = System.currentTimeMillis(); }
        return cache;
    }

    public List<Card> registeredNow() { return Reg.query(cardsKey, "/s").map(o -> parse(o, cardsKey)).orElse(List.of()); }

    /** Slot of a card among the registered ones (0 is the first after ccacc's), or -1 when it is not registered. */
    public int position(String id) {
        var l = registered();
        return IntStream.range(0, l.size()).filter(i -> l.get(i).id().equalsIgnoreCase(id)).findFirst().orElse(-1);
    }

    /** ccacc's own count: 1 or 2. */
    public int legacy() { return Reg.query(legacyKey, "/v", LEGACY_VALUE).map(Cards::parseLegacy).orElse(1); }

    public boolean legacy(int n) { return Reg.add(legacyKey, LEGACY_VALUE, "REG_DWORD", n == 2 ? "2" : "1"); }

    /** How long the DLL makes the band when it is created now. */
    public int total() { return total(legacy(), registeredNow().size()); }

    static int total(int legacy, int registered) { return Math.min(MAX, Math.max(1, legacy) + Math.min(MAX, registered)); }

    static int parseLegacy(String out) {
        return out.lines().map(VALUE::matcher).filter(Matcher::matches).filter(m -> m.group(2).equals("REG_DWORD")).map(m -> Long.decode(m.group(3))).findFirst().orElse(1L) == 2 ? 2 : 1;
    }

    /** Reads the output of {@code reg query <cardsKey> /s}: every direct subkey is a card, whether or not its values are complete (the DLL counts subkeys, so must we). */
    static List<Card> parse(String out, String cardsKey) {
        var parent = cardsKey.replaceFirst("^HKCU\\\\", "HKEY_CURRENT_USER\\\\") + "\\";
        var found = new ArrayList<Card>();
        Card cur = null;
        for (var line : out.split("\\R")) {
            if (line.startsWith("HKEY_")) {
                var rest = line.trim().length() > parent.length() && line.regionMatches(true, 0, parent, 0, parent.length()) ? line.trim().substring(parent.length()) : "";
                cur = rest.isEmpty() || rest.contains("\\") ? null : new Card(rest, "", 0);
                if (cur != null) found.add(cur);
                continue;
            }
            var m = VALUE.matcher(line);
            if (cur == null || !m.matches()) continue;
            if (m.group(1).equalsIgnoreCase("title") && m.group(2).equals("REG_SZ")) cur.title(m.group(3));
            else if (m.group(1).equalsIgnoreCase("order") && m.group(2).equals("REG_DWORD")) cur.order((int) Math.min(Integer.MAX_VALUE, Long.decode(m.group(3))));
        }
        found.sort(Comparator.comparingInt((Card c) -> c.order()).thenComparing((Card c) -> c.id(), String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(found);
    }
}
