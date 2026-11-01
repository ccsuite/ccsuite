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
    List<KeyRef> keys = new ArrayList<>();

    public Optional<KeyRef> key(String fingerprint) {
        return keys.stream().filter(k -> k.fingerprint().equals(fingerprint)).findFirst();
    }

    public boolean expired(Instant now) {
        return expires != null && now.isAfter(Instant.parse(expires));
    }
}
