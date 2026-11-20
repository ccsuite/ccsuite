package ccsuite.taskbarslot;

import module java.base;

/** The three reg.exe calls the contract needs. A failed call is an empty result or false, never an exception: the taskbar must not break because the registry did not answer. */
final class Reg {
    private Reg() {}

    static Optional<String> query(String... args) {
        var cmd = new ArrayList<String>(List.of("reg.exe", "query"));
        cmd.addAll(List.of(args));
        return run(cmd);
    }

    static boolean add(String key, String name, String type, String data) { return run(List.of("reg.exe", "add", key, "/v", name, "/t", type, "/d", data, "/f")).isPresent(); }

    static boolean delete(String key) { return run(List.of("reg.exe", "delete", key, "/f")).isPresent(); }

    static Optional<String> run(List<String> cmd) {
        try {
            var p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return p.waitFor() == 0 ? Optional.of(out) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
