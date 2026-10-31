package ccsuite.base.config;

import ccsuite.base.model.CCAPIsCredentials;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Loads and saves the shared credentials. The file is JSON, {@code ~/ccsuite/base/credentials.json}.
 * The first load on a machine that only has the old {@code ~/xyz-jphil/ccapis/CCAPIsCredentials.xml}
 * imports it into the JSON file and leaves the XML untouched, for the older tools still reading it
 * (they do not see later changes: stop them before relying on the JSON).
 * A path that ends in {@code .xml} is read and written as the old XML, as before.
 */
public class CredentialsManager {

    static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    final Path path;
    final Path legacyXml;
    boolean importedFromLegacy;

    /** One lock per file for the threads of this process; the file lock below covers other processes. */
    static final Map<Path, ReentrantLock> IN_PROCESS = new ConcurrentHashMap<>();

    /** A change to the accounts as one step. */
    @FunctionalInterface
    public interface Change {
        void apply(CCAPIsCredentials credentials);
    }

    /**
     * Changes the file safely when several writers may be at it at once (threads here, the CLI and the app as two
     * processes): it takes this process's lock and an operating-system lock on {@code <file>.lock}, reads the file as
     * it is now, applies the change, saves, and lets go. So every change is made to the latest file and none is lost.
     * A change that throws saves nothing. Returns the accounts as saved.
     */
    public CCAPIsCredentials update(Change change) throws JAXBException, IOException {
        var local = IN_PROCESS.computeIfAbsent(path.toAbsolutePath().normalize(), p -> new ReentrantLock());
        local.lock();
        try {
            var dir = path.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            try (var channel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = channel.lock()) {
                var fresh = credentialsFileExists() ? load() : new CCAPIsCredentials();
                change.apply(fresh);
                save(fresh);
                return fresh;
            }
        } finally {
            local.unlock();
        }
    }

    /** When the file was last written, to notice a change made by another process; 0 when there is none. */
    public long lastModified() {
        try { return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0; }
        catch (IOException e) { return 0; }
    }

    /** The default JSON file, importing from the default old XML when the JSON does not exist yet. */
    public CredentialsManager() {
        this(getDefaultCredentialsPath(), getLegacyXmlPath());
    }

    public CredentialsManager(String customPath) { this(Path.of(customPath)); }

    /** A custom file: {@code .xml} is the old XML format, anything else JSON; neither imports from anywhere. */
    public CredentialsManager(Path customPath) { this(customPath, null); }

    CredentialsManager(Path path, Path legacyXml) {
        this.path = path;
        this.legacyXml = legacyXml;
    }

    public static Path getDefaultCredentialsPath() {
        return Path.of(System.getProperty("user.home"), "ccsuite", "base", "credentials.json");
    }

    public static Path getLegacyXmlPath() {
        return Path.of(System.getProperty("user.home"), "xyz-jphil", "ccapis", "CCAPIsCredentials.xml");
    }

    public Path getCredentialsPath() { return path; }

    public boolean isXml() { return path.getFileName().toString().endsWith(".xml"); }

    /** True when the last {@link #load} had to import the old XML because the JSON did not exist. */
    public boolean importedFromLegacy() { return importedFromLegacy; }

    public boolean credentialsFileExists() { return Files.exists(path) || (legacyXml != null && Files.exists(legacyXml)); }

    public CCAPIsCredentials load() throws JAXBException, IOException {
        importedFromLegacy = false;
        if (isXml()) return readXml(path);
        if (Files.exists(path)) return JSON.readValue(path.toFile(), CCAPIsCredentials.class);
        if (legacyXml != null && Files.exists(legacyXml)) {
            var imported = readXml(legacyXml);
            save(imported);
            importedFromLegacy = true;
            return imported;
        }
        throw new IOException("Credentials file not found: " + path + "\nCreate it with your accounts (ccacc account add), or put the old " + legacyXml + " in place to import it.");
    }

    public void save(CCAPIsCredentials credentials) throws JAXBException, IOException {
        var dir = path.getParent();
        if (dir != null) Files.createDirectories(dir);
        if (isXml()) { writeXml(credentials); return; }
        var tmp = path.resolveSibling(path.getFileName() + ".tmp");
        JSON.writeValue(tmp.toFile(), credentials);
        SafeFiles.replace(tmp, path);
    }

    static CCAPIsCredentials readXml(Path file) throws JAXBException, IOException {
        if (!Files.exists(file)) throw new IOException("Credentials file not found: " + file);
        return (CCAPIsCredentials) JAXBContext.newInstance(CCAPIsCredentials.class).createUnmarshaller().unmarshal(file.toFile());
    }

    void writeXml(CCAPIsCredentials credentials) throws JAXBException {
        var marshaller = JAXBContext.newInstance(CCAPIsCredentials.class).createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        marshaller.marshal(credentials, path.toFile());
    }
}
