package ccsuite.ccacc;

import ccsuite.base.config.AccountEditor;
import ccsuite.base.config.CredentialsManager;
import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.CCAPIsCredentials;
import jakarta.xml.bind.JAXBException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Adds, removes and changes the accounts. Every change goes through {@link CredentialsManager#update}: made to the file
 * as it is now, under a lock other threads and other processes (the CLI) respect, so no one's edit is lost. The result
 * is then taken into the one credentials object the switcher reads, and {@code changed} lets the app poll and redraw.
 * A value that does not make sense is refused with a message and changes nothing, on disk or in memory.
 */
final class Accounts {

    final CredentialsManager manager;
    final CCAPIsCredentials creds;
    final Runnable changed;

    Accounts(CredentialsManager manager, CCAPIsCredentials creds, Runnable changed) {
        this.manager = manager;
        this.creds = creds;
        this.changed = changed;
    }

    List<CCAPICredential> list() { return List.copyOf(creds.credentials()); }

    /** Sets each property of the account in one step. */
    void set(String id, Map<String, String> props) throws IOException {
        change(all -> props.forEach((k, v) -> AccountEditor.set(all, id, k, v)));
    }

    void add(Map<String, String> props) throws IOException {
        change(all -> AccountEditor.add(all, props));
    }

    /**
     * A free account: tier 0 with a web session key and an organisation id ({@link FreeGauges#free}). The short name is its id and its name. A missing or malformed
     * field is refused with a message that never contains the key, and nothing is written, so no other account changes.
     */
    void addFree(String shortName, String email, String orgId, String key) throws IOException {
        var id = shortName == null ? "" : shortName.trim();
        if (id.isEmpty()) throw new IllegalArgumentException("the short name is missing");
        if (id.matches(".*\\s.*")) throw new IllegalArgumentException("the short name has no spaces (it is the account's id)");
        if (email == null || !email.trim().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw new IllegalArgumentException("the e-mail does not look like an e-mail address");
        if (orgId == null || orgId.isBlank()) throw new IllegalArgumentException("the organisation id is missing");
        if (orgId.trim().matches(".*\\s.*")) throw new IllegalArgumentException("the organisation id has no spaces");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("the session key is missing");
        if (key.trim().matches(".*\\s.*")) throw new IllegalArgumentException("the session key has no spaces or line breaks (paste only the key)");
        var p = new java.util.LinkedHashMap<String, String>();
        p.put("id", id);
        p.put("name", id);
        p.put("email", email.trim());
        p.put("orgId", orgId.trim());
        p.put("key", key.trim());
        p.put("tier", "0");
        p.put("active", "on");
        p.put("trackUsage", "on");
        p.put("trackChats", "on");
        add(p);
    }

    /** Takes the account out of the file; the login saved for switching to it stays on disk. */
    void remove(String id) throws IOException {
        change(all -> AccountEditor.remove(all, id));
    }

    private void change(CredentialsManager.Change edit) throws IOException {
        try {
            adopt(creds, manager.update(edit));
        } catch (JAXBException e) {
            throw new IOException("cannot save the accounts: " + e.getMessage(), e);
        }
        changed.run();
    }

    /** Makes the shared object hold what is on disk; one reference swap per list, so a reader never sees it half done. */
    static void adopt(CCAPIsCredentials shared, CCAPIsCredentials fresh) {
        shared.credentials(new CopyOnWriteArrayList<>(fresh.credentials()));
        shared.oauthCredentials(new CopyOnWriteArrayList<>(fresh.oauthCredentials()));
        shared.circuitBreakerConfig(fresh.circuitBreakerConfig());
    }
}
