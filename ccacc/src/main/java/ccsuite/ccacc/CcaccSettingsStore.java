package ccsuite.ccacc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads and writes the settings file; a missing or unreadable file gives the defaults and is written on the first change. */
public final class CcaccSettingsStore {

    static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    final Path file;

    public CcaccSettingsStore() {
        this(Path.of(System.getProperty("user.home"), "ccsuite", "ccacc", "settings.json"));
    }

    public CcaccSettingsStore(Path file) { this.file = file; }

    public Path file() { return file; }

    public CcaccSettings load() {
        try {
            return Files.exists(file) ? JSON.readValue(file.toFile(), CcaccSettings.class) : new CcaccSettings();
        } catch (IOException e) {
            System.err.println("ccacc: cannot read " + file + " (" + e.getMessage() + "), using defaults");
            return new CcaccSettings();
        }
    }

    public void save(CcaccSettings s) {
        try {
            Files.createDirectories(file.getParent());
            var tmp = file.resolveSibling(file.getFileName() + ".tmp");
            JSON.writeValue(tmp.toFile(), s);
            ccsuite.base.config.SafeFiles.replace(tmp, file);
        } catch (IOException e) {
            System.err.println("ccacc: cannot write " + file + ": " + e.getMessage());
        }
    }
}
