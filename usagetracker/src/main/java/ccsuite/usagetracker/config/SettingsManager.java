package ccsuite.usagetracker.config;

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
 * The usage widget's settings, {@code ~/ccsuite/usagetracker/settings.json}. The first load on a machine that only has
 * the old {@code ~/xyz-jphil/ccapis/usage_tracker/CCAPIsUsageTrackerSettings.xml} imports it into the JSON file and
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
        this.settingsPath = Paths.get(home, "ccsuite", "usagetracker", "settings.json");
        this.legacyXml = Paths.get(home, "xyz-jphil", "ccapis", "usage_tracker", "CCAPIsUsageTrackerSettings.xml");
        makeDirectory(settingsPath);
    }

    /** A custom file, for tests or another place: {@code .xml} is the old format, anything else JSON; nothing is imported. */
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

    public Settings load() throws JAXBException, IOException {
        if (isXml()) return Files.exists(settingsPath) ? readXml(settingsPath) : fresh();
        if (Files.exists(settingsPath)) return JSON.readValue(settingsPath.toFile(), Settings.class);
        var settings = legacyXml != null && Files.exists(legacyXml) ? readXml(legacyXml) : createEmptyTemplate();
        save(settings);
        return settings;
    }

    Settings fresh() throws JAXBException {
        var settings = createEmptyTemplate();
        save(settings);
        return settings;
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

    /** An empty settings template with the UI defaults. */
    public Settings createEmptyTemplate() {
        var settings = new Settings();
        var ui = new Settings.UISettings()
                .layout("horizontal")
                .sideBar(false)
                .showTime("timeRemaining")
                .idMaxLen(10)
                .usageRefreshIntervalSecs(60)
                .usageFetchSpacingMs(1200)
                .usageMaxRetries(4)
                .usageRetryBaseMs(800)
                .usageRetryMaxMs(20000)
                .pingIntervalDurSec(3600)
                .pingMessageVisible(true);
        settings.ui(ui);
        return settings;
    }

    public Path getSettingsPath() {
        return settingsPath;
    }
}
