package ccsuite.ccacc.migrate;

import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.CCAPIsCredentials;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Brings the old XML's accounts into the JSON before the XML is put away. The JSON wins wherever both have an account
 * (it is where changes have been made since the first import); an account only the XML has is added; every field that
 * differs is reported, with the session key never shown. Whether Claude Code is active on an account is not compared:
 * it is read from the live login.
 */
public final class CredentialsMerge {

    /** Adds what the XML has and the JSON lacks; returns one line per account added and per difference found. */
    public static List<String> merge(CCAPIsCredentials json, CCAPIsCredentials xml) {
        var notes = new ArrayList<String>();
        for (var x : xml.credentials()) {
            var j = json.getById(x.id());
            if (j == null) {
                json.credentials().add(x);
                notes.add("added account " + x.id() + " from the old file");
                continue;
            }
            var mine = view(j);
            view(x).forEach((field, theirs) -> {
                if (!Objects.equals(mine.get(field), theirs))
                    notes.add("%s.%s differs (kept: %s, old file: %s)".formatted(x.id(), field, shown(field, mine.get(field)), shown(field, theirs)));
            });
        }
        return notes;
    }

    static Map<String, Object> view(CCAPICredential c) {
        var m = new LinkedHashMap<String, Object>();
        m.put("name", c.name());
        m.put("email", c.email());
        m.put("ccapiBaseUrl", c.ccapiBaseUrl());
        m.put("orgId", c.orgId());
        m.put("key", c.key());
        m.put("active", c.active());
        m.put("trackUsage", c.trackUsage());
        m.put("ping", c.ping());
        m.put("tier", c.tier());
        m.put("ua", c.ua());
        m.put("trackChats", c.trackChats());
        m.put("claudecodelimit", c.claudecodelimit());
        return m;
    }

    static String shown(String field, Object v) { return field.equals("key") ? "(a key)" : String.valueOf(v); }

    private CredentialsMerge() {}
}
