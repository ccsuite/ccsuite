package ccsuite.accounts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** The login in {@code <configDir>/.credentials.json}. */
public final class FileCredentials implements CredentialsBackend {

    private final Path file;

    public FileCredentials(Path file) { this.file = file; }

    @Override public String read() throws IOException {
        var text = AtomicFiles.readString(file);
        return text.isBlank() ? null : text;
    }

    @Override public void write(String json) throws IOException { AtomicFiles.writeString(file, json); }

    @Override public String describe() { return file.toString(); }

    boolean exists() { return Files.exists(file); }
}
