package ccsuite.ccacc;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Only one resident ccacc per user: two would run two watchers switching accounts against each other and two
 * writers of the same files. The lock is an operating-system file lock, so it goes away by itself when the process
 * ends, however it ends; there is no stale lock file to clean up.
 */
final class SingleInstance {

    final FileChannel channel;
    final FileLock lock;

    private SingleInstance(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** The lock, or null when another ccacc already holds it. Keep the returned object for the life of the process. */
    static SingleInstance acquire(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        var channel = FileChannel.open(file, CREATE, WRITE);
        var lock = channel.tryLock();
        if (lock == null) { channel.close(); return null; }
        return new SingleInstance(channel, lock);
    }

    static Path defaultFile() { return Path.of(System.getProperty("user.home"), "ccsuite", "ccacc", "app.lock"); }
}
