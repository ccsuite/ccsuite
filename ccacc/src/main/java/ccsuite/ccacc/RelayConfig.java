package ccsuite.ccacc;

import module java.base;
import ccsuite.ccrelay.RelayServer;

/**
 * Where the relay part connects to and with what: the relay's address in {@code ~/ccsuite/ccacc/relay.url} and the
 * provider secret in {@code ~/ccsuite/ccacc/relay.secret}. Two files of their own, so the command line can set them
 * while the app runs without the app's settings overwriting them. The secret never leaves this machine; the relay
 * server is given only its SHA-256.
 */
final class RelayConfig {

    static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    final Path dir;

    RelayConfig(Path dir) {
        this.dir = dir;
    }

    static RelayConfig standard() {
        return new RelayConfig(Path.of(System.getProperty("user.home"), "ccsuite", "ccacc"));
    }

    Optional<String> url() throws IOException {
        var f = dir.resolve("relay.url");
        return Files.exists(f) ? Optional.of(Files.readString(f).strip()).filter(s -> !s.isEmpty()) : Optional.empty();
    }

    void url(String url) throws IOException {
        var u = URI.create(url.strip());
        if (u.getScheme() == null || !u.getScheme().startsWith("http")) throw new IllegalArgumentException("an http(s) address, not " + url);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("relay.url"), u.toString());
    }

    Optional<String> secret() throws IOException {
        var f = dir.resolve("relay.secret");
        return Files.exists(f) ? Optional.of(Files.readString(f).strip()).filter(s -> !s.isEmpty()) : Optional.empty();
    }

    /** The secret, made now when there is none (or when {@code fresh}); returns it. */
    String ensureSecret(boolean fresh) throws IOException {
        var have = secret();
        if (have.isPresent() && !fresh) return have.get();
        var r = new SecureRandom();
        var sb = new StringBuilder("ccrs_");
        for (var i = 0; i < 40; i++) sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        Files.createDirectories(dir);
        var f = dir.resolve("relay.secret");
        Files.writeString(f, sb);
        try { Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException windows) { }
        return sb.toString();
    }

    /** "ccacc gate relay url <address>" and "ccacc gate relay secret [--new]". */
    String run(List<String> args) throws IOException {
        var verb = args.isEmpty() ? "" : args.get(0);
        return switch (verb) {
            case "url" -> {
                if (args.size() < 2) yield "relay address: " + url().orElse("(not set)");
                url(args.get(1));
                yield "relay address set to " + url().orElseThrow() + "\nnext: ccacc part relay on (the gate must be on too)";
            }
            case "secret" -> {
                var s = ensureSecret(args.contains("--new"));
                yield "provider secret kept in " + dir.resolve("relay.secret") + " (never printed)\n"
                        + "give the relay server this SHA-256, one line in its provider.sha256 file:\n" + RelayServer.hashHex(s);
            }
            default -> "usage: ccacc gate relay url <https://relay.example.org>\n       ccacc gate relay secret [--new]";
        };
    }
}
