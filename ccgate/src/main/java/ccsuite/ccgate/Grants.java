package ccsuite.ccgate;

import module java.base;
import datapotter.datahelper.Data;

/** The contents of grants.json. */
@Data
public final class Grants extends Grants_A {
    List<Grant> grants = new ArrayList<>();

    public Optional<Grant> byId(String id) {
        return grants.stream().filter(g -> g.id().equals(id)).findFirst();
    }

    public Optional<Grant> byKey(String fingerprint) {
        return grants.stream().filter(g -> g.key(fingerprint).isPresent()).findFirst();
    }
}
