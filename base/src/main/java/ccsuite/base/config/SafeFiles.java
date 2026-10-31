package ccsuite.base.config;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/** Replacing a file the way Windows allows: another process (a scanner, an indexer, a reader) may hold the target for a moment, so the rename is retried. */
public final class SafeFiles {

    /** Moves {@code tmp} over {@code target}, atomically where possible, retrying a few times when the target is briefly locked. */
    public static void replace(Path tmp, Path target) throws IOException {
        IOException last = null;
        for (var attempt = 1; attempt <= 6; attempt++) {
            try {
                Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(40L * attempt); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
        throw last;
    }

    private SafeFiles() {}
}
