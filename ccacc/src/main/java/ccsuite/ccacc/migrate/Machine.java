package ccsuite.ccacc.migrate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** What migrating needs from the real machine: the PATH, the Startup folder, an exe's own account of itself, a shortcut's target, and stopping a running exe. */
public final class Machine {

    public static Path home() { return Path.of(System.getProperty("user.home")); }

    public static List<Path> pathDirs() {
        var path = System.getenv("PATH");
        return path == null ? List.of() : Arrays.stream(path.split(File.pathSeparator)).filter(s -> !s.isBlank()).map(Path::of).filter(Files::isDirectory).toList();
    }

    public static Path startupDir() {
        var appData = System.getenv("APPDATA");
        return appData == null ? null : Path.of(appData, "Microsoft", "Windows", "Start Menu", "Programs", "Startup");
    }

    /** What a jr exe says about itself ({@code -Xjr:doctor}); null when it cannot be asked. */
    public static String doctor(Path exe) {
        return run(30, exe.toString(), "-Xjr:doctor");
    }

    /** The exe a Startup shortcut starts. */
    public static Optional<Path> shortcutTarget(Path lnk) {
        var text = run(30, "powershell", "-NoProfile", "-Command",
                "(New-Object -ComObject WScript.Shell).CreateShortcut('" + lnk.toString().replace("'", "''") + "').TargetPath");
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(Path.of(text.strip()));
    }

    /** Stops every running process of this exe; returns how many there were. */
    public static int stop(Path exe) {
        var target = exe.toAbsolutePath().normalize().toString();
        var running = ProcessHandle.allProcesses().filter(p -> p.info().command().map(c -> c.equalsIgnoreCase(target)).orElse(false)).toList();
        running.forEach(ProcessHandle::destroy);
        for (var p : running) {
            try { p.onExit().get(5, TimeUnit.SECONDS); }
            catch (Exception e) { p.destroyForcibly(); }
        }
        return running.size();
    }

    static String run(int seconds, String... command) {
        try {
            var p = new ProcessBuilder(command).redirectErrorStream(true).start();
            var out = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            return out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    private Machine() {}
}
