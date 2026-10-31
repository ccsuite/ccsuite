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
