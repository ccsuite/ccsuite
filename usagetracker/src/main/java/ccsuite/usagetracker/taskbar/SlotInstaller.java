package ccsuite.usagetracker.taskbar;

import module java.base;

/** Pulls the taskbar slot DLL out of the jar and registers it for the current user (no admin). */
public final class SlotInstaller {
    static final String RESOURCE = "/ccsuite/usagetracker/taskbar/ccuslot.dll";
    static final String KEY = "HKCU\\Software\\Classes\\CLSID\\{8CB51477-F1FA-4633-ADEB-31F7FB414452}\\InprocServer32";

    private SlotInstaller() {}

    public static Path directory() { return Path.of(System.getProperty("user.home"), "ccsuite", "usagetracker", "taskbar"); }

    /** Extracts the DLL (named by its hash, so a changed build never collides with one Explorer has loaded) and registers it. Safe to call repeatedly. */
    public static Path install() throws IOException, InterruptedException {
        byte[] bytes;
        try (var in = SlotInstaller.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("the jar carries no " + RESOURCE);
            bytes = in.readAllBytes();
        }
        var dll = directory().resolve("ccuslot-" + hash(bytes) + ".dll");
        Files.createDirectories(directory());
        if (!Files.exists(dll) || Files.size(dll) != bytes.length) Files.write(dll, bytes);
        if (!registeredPath().filter(p -> p.equalsIgnoreCase(dll.toString())).isPresent()) run("regsvr32.exe", "/s", dll.toString());
        return dll;
    }

    /** Removes the registration (the DLL file stays; Explorer keeps it loaded until it restarts). */
    public static void uninstall() throws IOException, InterruptedException {
        var p = registeredPath();
        if (p.isPresent()) run("regsvr32.exe", "/u", "/s", p.get());
    }

    public static Optional<String> registeredPath() throws IOException, InterruptedException {
        var pb = new ProcessBuilder("reg.exe", "query", KEY, "/ve").redirectErrorStream(true);
        var proc = pb.start();
        var text = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (proc.waitFor() != 0) return Optional.empty();
        var m = java.util.regex.Pattern.compile("REG_SZ\\s+(.+?)\\s*$", java.util.regex.Pattern.MULTILINE).matcher(text);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 4); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static void run(String... cmd) throws IOException, InterruptedException {
        var proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        proc.getInputStream().readAllBytes();
        if (proc.waitFor() != 0) throw new IOException(cmd[0] + " failed with exit code " + proc.exitValue());
    }
}
