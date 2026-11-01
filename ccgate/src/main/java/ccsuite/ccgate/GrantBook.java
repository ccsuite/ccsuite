package ccsuite.ccgate;

import module java.base;
import com.fasterxml.jackson.databind.*;

/**
 * grants.json in the gate's folder. The command line is its only writer; the running gate re-reads it whenever the
 * file changes, so a grant or key added from the command line works at once.
 */
public final class GrantBook {

    static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    final Path file;
    Grants grants = new Grants();
    FileTime seen;

    public GrantBook(Path file) {
        this.file = file;
    }

    public static GrantBook open() {
        return new GrantBook(GateHome.dir().resolve("grants.json"));
    }

    public synchronized Grants current() {
        try {
            var t = Files.exists(file) ? Files.getLastModifiedTime(file) : null;
            if (t != null && !t.equals(seen)) {
                grants = JSON.readValue(file.toFile(), Grants.class);
                seen = t;
            }
        } catch (IOException e) {
            System.err.println("ccgate: " + file + " unreadable, keeping the last good copy: " + e.getMessage());
        }
        return grants;
    }

    public synchronized void save(Grants g) throws IOException {
        Files.createDirectories(file.getParent());
        var tmp = file.resolveSibling(file.getFileName() + ".tmp");
        JSON.writeValue(tmp.toFile(), g);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        grants = g;
        seen = Files.getLastModifiedTime(file);
    }
}
