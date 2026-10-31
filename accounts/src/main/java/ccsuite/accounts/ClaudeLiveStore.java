package ccsuite.accounts;

import com.fasterxml.jackson.databind.*;
import org.slf4j.*;

import java.io.IOException;

/**
 * Reads and writes Claude Code's <em>live</em> files: the active OAuth token in
 * {@code .credentials.json} and the {@code oauthAccount} identity in the global
 * config. Writes are atomic; the global-config update is surgical (only the
 * {@code oauthAccount} object is replaced) to preserve the rest of that large file.
 */
public final class ClaudeLiveStore {

    private static final Logger log = LoggerFactory.getLogger(ClaudeLiveStore.class);

    private final ClaudeCodePaths paths;
    private final ObjectMapper mapper;

    public ClaudeLiveStore(ClaudeCodePaths paths, ObjectMapper mapper) {
        this.paths = paths;
        this.mapper = mapper;
    }

    /** Active credentials tree, or {@code null} when there is no login on disk. */
    public JsonNode readCredentials() throws IOException {
        var txt = paths.backend().read();
        return txt == null || txt.isBlank() ? null : mapper.readTree(txt);
    }

    public boolean hasLogin() throws IOException {
        var c = readCredentials();
        return c != null && c.has("claudeAiOauth");
    }

    public void writeCredentials(JsonNode credentials) throws IOException {
        var json = paths.backend().describe().startsWith("the macOS Keychain")
                ? mapper.writeValueAsString(credentials) : mapper.writerWithDefaultPrettyPrinter().writeValueAsString(credentials);
        paths.backend().write(json);
        log.info("Wrote active credentials to {}", paths.backend().describe());
    }

    /** The live {@code oauthAccount} identity block, or {@code null} if absent. */
    public JsonNode readOauthAccount() throws IOException {
        var txt = AtomicFiles.readString(paths.globalConfigFile());
        if (txt.isBlank()) return null;
        var node = mapper.readTree(txt).get("oauthAccount");
        return (node == null || node.isNull()) ? null : node;
    }

    /** Replace only the {@code oauthAccount} object in the global config (atomic). */
    public void writeOauthAccount(JsonNode oauthAccount) throws IOException {
        var file = paths.globalConfigFile();
        var json = AtomicFiles.readString(file);
        var block = AtomicFiles.reindent(
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(oauthAccount), "  ");
        if (json.isBlank()) {
            AtomicFiles.writeString(file, "{\n  \"oauthAccount\": " + block + "\n}\n");
            return;
        }
        var span = AtomicFiles.objectValueSpan(json, "oauthAccount");
        String out;
        if (span != null) {
            out = json.substring(0, span[0]) + block + json.substring(span[1]);
        } else {
            var brace = json.indexOf('{');
            if (brace < 0) throw new IOException("Global config is not a JSON object: " + file);
            out = json.substring(0, brace + 1) + "\n  \"oauthAccount\": " + block + ","
                    + json.substring(brace + 1);
        }
        AtomicFiles.writeString(file, out);
        log.info("Updated oauthAccount in {}", file);
    }
}
