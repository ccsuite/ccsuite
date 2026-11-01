package ccsuite.ccrelay;

import module java.base;

/**
 * Runs the relay on its own: {@code ccrelay serve [--port 7790] [--secret-hash-file <file>]}. The file holds the
 * SHA-256 (hex) of the provider secret, which the owner's ccacc prints with {@code ccacc relay secret}; the secret
 * itself never reaches the server. {@code ccrelay hash} reads a secret on stdin and prints its hash.
 */
public final class RelayMain {

    public static void main(String[] args) throws Exception {
        var list = List.of(args);
        if (list.isEmpty() || list.get(0).equals("--help")) {
            System.out.println("ccrelay serve [--port 7790] [--secret-hash-file ~/.ccrelay/provider.sha256]\nccrelay hash   (secret on stdin, prints its SHA-256)");
            return;
        }
        if (list.get(0).equals("hash")) {
            System.out.println(RelayServer.hashHex(new String(System.in.readAllBytes(), StandardCharsets.UTF_8).strip()));
            return;
        }
        var port = Integer.parseInt(option(list, "--port", "7790"));
        var file = Path.of(option(list, "--secret-hash-file", Path.of(System.getProperty("user.home"), ".ccrelay", "provider.sha256").toString()));
        var relay = RelayServer.standard(port, Files.readString(file)).start();
        System.out.println("ccrelay on http://127.0.0.1:" + relay.port() + " (put a web server with TLS in front of it)");
        Thread.currentThread().join();
    }

    static String option(List<String> args, String name, String fallback) {
        var i = args.indexOf(name);
        return i >= 0 && i + 1 < args.size() ? args.get(i + 1) : fallback;
    }

    private RelayMain() { }
}
