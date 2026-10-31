package ccsuite.ccacc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SingleInstanceTest {

    @Test void aSecondInstanceIsRefusedUntilTheFirstLetsGo(@TempDir Path dir) throws Exception {
        var file = dir.resolve("app.lock");
        var first = SingleInstance.acquire(file);
        assertNotNull(first);
        assertThrows(java.nio.channels.OverlappingFileLockException.class, () -> SingleInstance.acquire(file),
                "inside one JVM the second try is refused by an exception; another process gets null");
        first.lock.release();
        first.channel.close();
        var again = SingleInstance.acquire(file);
        assertNotNull(again);
        again.channel.close();
    }
}
