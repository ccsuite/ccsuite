package ccsuite.accounts;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Per-account snapshot store: one {@code <id>.json} file per registered account
 * under {@code <storeRoot>/claudecode-accounts/}. Keyed by CCAPICredential id
 * (stable and unique — unlike email, which can repeat across credentials).
 */
public final class AccountStore {

    private final Path dir;
    private final ObjectMapper mapper;

    public AccountStore(Path storeRoot, ObjectMapper mapper) {
        this.dir = storeRoot.resolve("claudecode-accounts");
        this.mapper = mapper;
    }

    /** Default store root: {@code <user.home>/xyz-jphil/ccapis}. */
    public static Path defaultStoreRoot() {
        return Paths.get(System.getProperty("user.home"), "xyz-jphil", "ccapis");
    }

    public Path dir() { return dir; }

    private Path file(String id) { return dir.resolve(id + ".json"); }

    public boolean isRegistered(String id) { return Files.exists(file(id)); }

    public ClaudeCodeAccount load(String id) throws IOException {
        return mapper.readValue(file(id).toFile(), ClaudeCodeAccount.class);
    }

    public void save(ClaudeCodeAccount account) throws IOException {
        AtomicFiles.writeString(file(account.id()),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(account));
    }

    public Set<String> registeredIds() throws IOException {
        if (!Files.isDirectory(dir)) return Set.of();
        try (var s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - ".json".length()))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }
}
