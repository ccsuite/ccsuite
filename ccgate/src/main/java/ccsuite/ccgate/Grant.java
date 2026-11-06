package ccsuite.ccgate;

import module java.base;
import datapotter.datahelper.Data;

/**
 * What a caller may do: which model families, how many quota points in all and per UTC day for each, until when, and
 * what for. Points: 1 point = 1% of one account's 5-hour window (see quotaweights).
 */
@Data
public final class Grant extends Grant_A {
    String id;
    String name;
    String kind;
    String purpose;
    String email;
    List<String> models = new ArrayList<>();
    Map<String, Double> budget = new LinkedHashMap<>();
    Map<String, Double> daily = new LinkedHashMap<>();
    int perMinute;
    String created;
    String expires;
    boolean revoked;
    /** Suspended until this instant (ISO), set from the Clients tab or `ccacc gate grant ban`; null when not suspended. */
    String bannedUntil;
    String banReason;
    /** Response speed limit in bytes per second; 0 = none. A streamed answer simply arrives slower. */
    int throttleBps;
    /** Over its per-minute rate the call waits for a free slot (up to 30 s) instead of being refused. */
    boolean smooth;
    List<KeyRef> keys = new ArrayList<>();

    public Optional<KeyRef> key(String fingerprint) {
        return keys.stream().filter(k -> k.fingerprint().equals(fingerprint)).findFirst();
    }

    public boolean suspended(Instant now) {
        return bannedUntil != null && now.isBefore(Instant.parse(bannedUntil));
    }

    public boolean expired(Instant now) {
        return expires != null && now.isAfter(Instant.parse(expires));
    }
}
