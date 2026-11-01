package ccsuite.ccgate;

import module java.base;

/** Makes keys and their fingerprints. A key is "ccg_" and 32 random letters and digits. */
public final class Keys {

    static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789", PREFIX = "ccg_";
    static final SecureRandom RANDOM = new SecureRandom();

    public static String newKey() {
        var sb = new StringBuilder(PREFIX);
        for (var i = 0; i < 32; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    public static String fingerprint(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.strip().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The key a caller sent: x-api-key, or an Authorization: Bearer token (Claude Code sends that when given a token). */
    public static String fromHeaders(Map<String, List<String>> headers) {
        var api = first(headers, "x-api-key");
        if (api != null && !api.isBlank()) return api.strip();
        var auth = first(headers, "authorization");
        return auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.substring(7).strip() : null;
    }

    static String first(Map<String, List<String>> headers, String name) {
        for (var e : headers.entrySet())
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().get(0);
        return null;
    }

    private Keys() { }
}
