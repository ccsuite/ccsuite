package ccsuite.accounts;

import java.io.IOException;

/** Where Claude Code keeps its OAuth login: a file on Windows and Linux, the Keychain on macOS. The content is the same JSON either way. */
public interface CredentialsBackend {

    /** The login as JSON text, or {@code null} when there is none. */
    String read() throws IOException;

    void write(String json) throws IOException;

    /** Where it lives, for messages. */
    String describe();
}
