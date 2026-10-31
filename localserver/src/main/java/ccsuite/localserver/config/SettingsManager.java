package ccsuite.localserver.config;

import ccsuite.base.config.SafeFiles;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The local proxy's settings, {@code ~/ccsuite/localserver/settings.json}. The first load on a machine that only has
 * the old {@code ~/xyz-jphil/ccapis/cclocalserver/CCAPIsLocalServerSettings.xml} imports it into the JSON file and
 * leaves the XML untouched. A path ending in {@code .xml} is read and written as the old XML, as before.
 */
public class SettingsManager {

    static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path settingsPath;
    private final Path legacyXml;

    public SettingsManager() {
        var home = System.getProperty("user.home");
        this.settingsPath = Paths.get(home, "ccsuite", "localserver", "settings.json");
        this.legacyXml = Paths.get(home, "xyz-jphil", "ccapis", "cclocalserver", "CCAPIsLocalServerSettings.xml");
        makeDirectory(settingsPath);
    }

    /** A custom file: {@code .xml} is the old format, anything else JSON; nothing is imported. */
    public SettingsManager(Path settingsPath) {
        this.settingsPath = settingsPath;
        this.legacyXml = null;
        makeDirectory(settingsPath);
    }

    static void makeDirectory(Path file) {
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create settings directory: " + file.getParent(), e);
        }
    }

    boolean isXml() { return settingsPath.getFileName().toString().endsWith(".xml"); }

    public Settings load() throws JAXBException {
        try {
            if (isXml() && Files.exists(settingsPath)) return readXml(settingsPath);
            if (!isXml() && Files.exists(settingsPath)) return JSON.readValue(settingsPath.toFile(), Settings.class);
            // Write the effective defaults (not a blank file) so the file says what there is to edit.
            var settings = !isXml() && legacyXml != null && Files.exists(legacyXml) ? readXml(legacyXml)
                    : new Settings().port(8790).usageRefreshIntervalSecs(60);
            save(settings);
            return settings;
        } catch (IOException e) {
            throw new JAXBException("cannot read " + settingsPath + ": " + e.getMessage(), e);
        }
    }

    public void save(Settings settings) throws JAXBException {
        try {
            if (isXml()) {
                var marshaller = JAXBContext.newInstance(Settings.class).createMarshaller();
                marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
                marshaller.marshal(settings, settingsPath.toFile());
                return;
            }
            var tmp = settingsPath.resolveSibling(settingsPath.getFileName() + ".tmp");
            JSON.writeValue(tmp.toFile(), settings);
            SafeFiles.replace(tmp, settingsPath);
        } catch (IOException e) {
            throw new JAXBException("cannot write " + settingsPath + ": " + e.getMessage(), e);
        }
    }

    static Settings readXml(Path file) throws JAXBException {
        return (Settings) JAXBContext.newInstance(Settings.class).createUnmarshaller().unmarshal(file.toFile());
    }

    public Path settingsPath() { return settingsPath; }
}
