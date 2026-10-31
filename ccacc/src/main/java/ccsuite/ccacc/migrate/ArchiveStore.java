package ccsuite.ccacc.migrate;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Puts files away safely and gets them back: each archive is a folder {@code <root>/<yyyyMMdd-HHmmss>/} holding the
 * files and a {@code manifest.json} that says where each came from and its SHA-256. Nothing is ever deleted;
 * a restore puts a file back only where nothing is, and says so for the ones it left.
 */
public final class ArchiveStore {

    static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** The manifest file of one archive. */
    @Data
    @Accessors(fluent = true, chain = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE)
    public static class Manifest {
        String created;
        List<ArchiveEntry> items = new ArrayList<>();
    }

    final Path root;

    public ArchiveStore(Path root) { this.root = root; }

    /** Moves the items into a new archive folder and returns its name. A file that is already gone is skipped. */
    public String archive(List<LegacyItem> items) throws IOException {
        var stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        var dir = root.resolve(stamp);
        Files.createDirectories(dir.resolve("files"));
        var manifest = new Manifest().created(LocalDateTime.now().toString());
        try {
            var n = 0;
            for (var item : items) {
                if (!Files.isRegularFile(item.path())) continue;
                var target = dir.resolve("files").resolve("%02d-%s".formatted(++n, item.path().getFileName()));
                var sha = sha256(item.path());
                Files.move(item.path(), target);
                manifest.items().add(new ArchiveEntry().kind(item.kind()).note(item.note()).original(item.path().toString())
                        .archived(dir.relativize(target).toString().replace('\\', '/')).sha256(sha));
            }
        } finally {
            JSON.writeValue(dir.resolve("manifest.json").toFile(), manifest);
        }
        return stamp;
    }

    /** Puts the archive's files back where they came from, except where something is there now; returns what happened to each. */
    public List<String> restore(String stamp) throws IOException {
        var dir = root.resolve(stamp);
        var manifest = JSON.readValue(dir.resolve("manifest.json").toFile(), Manifest.class);
        var out = new ArrayList<String>();
        for (var e : manifest.items()) {
            var from = dir.resolve(e.archived());
            var to = Path.of(e.original());
            if (!Files.isRegularFile(from)) { out.add("missing in the archive: " + e.original()); continue; }
            if (Files.exists(to)) { out.add("left in the archive, something is already at " + to); continue; }
            Files.createDirectories(to.getParent());
            Files.move(from, to);
            out.add("restored " + to + (sha256(to).equals(e.sha256()) ? "" : "  (checksum differs from the archived one!)"));
        }
        return out;
    }

    /** The archives, oldest first. */
    public List<String> stamps() throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(d -> Files.isRegularFile(d.resolve("manifest.json"))).map(d -> d.getFileName().toString()).sorted().toList();
        }
    }

    public Path folder(String stamp) { return root.resolve(stamp); }

    static String sha256(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            var md = MessageDigest.getInstance("SHA-256");
            var buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
