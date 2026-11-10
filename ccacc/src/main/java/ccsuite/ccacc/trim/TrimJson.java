package ccsuite.ccacc.trim;

import module java.base;
import ccsuite.base.config.SafeFiles;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Reads and writes the trimmer's two small JSON files; a missing or unreadable file gives the defaults. */
final class TrimJson {

    static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).enable(SerializationFeature.INDENT_OUTPUT);

    static Path home() { return Path.of(System.getProperty("user.home"), "ccsuite", "ccacc"); }

    static <T> T load(Path file, Class<T> type, Supplier<T> defaults) {
        try {
            return Files.exists(file) ? JSON.readValue(file.toFile(), type) : defaults.get();
        } catch (IOException e) {
            return defaults.get();
        }
    }

    static void save(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            var tmp = file.resolveSibling(file.getFileName() + ".tmp");
            JSON.writeValue(tmp.toFile(), value);
            SafeFiles.replace(tmp, file);
        } catch (IOException e) {
            System.err.println("ccacc trim: cannot write " + file + ": " + e.getMessage());
        }
    }

    private TrimJson() {}
}
