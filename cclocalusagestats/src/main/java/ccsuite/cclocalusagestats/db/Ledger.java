package ccsuite.cclocalusagestats.db;

import ccsuite.cclocalusagestats.model.*;
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.engine.ComponentFile;
import datapotter.arcadedbhelper.InitDoc;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;

public final class Ledger implements AutoCloseable {

    public static final Path HOME = System.getenv("CCLOCALUSAGESTATS_HOME") != null
        ? Path.of(System.getenv("CCLOCALUSAGESTATS_HOME"))
        : Path.of(System.getProperty("user.home"), "ccsuite", "cclocalusagestats");

    final FileChannel lockFile;
    final FileLock lock;
    final DatabaseFactory factory;
    public final Database db;

    public Ledger(Path dir) {
        try {
            Files.createDirectories(dir);
            lockFile = FileChannel.open(dir.resolve("ledger.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockFile.tryLock();
        } catch (IOException | OverlappingFileLockException e) {
            throw new LedgerBusyException(dir, e);
        }
        if (lock == null) throw new LedgerBusyException(dir, null);
        factory = new DatabaseFactory(dir.resolve("database").toString());
        db = factory.exists() ? factory.open(ComponentFile.MODE.READ_WRITE) : factory.create();
        InitDoc.initDocTypes(db, UsageRow.TYPEDEF, SessionInfo.TYPEDEF, FileState.TYPEDEF, SwitchEvent.TYPEDEF, ActiveMinute.TYPEDEF, Reading.TYPEDEF);
    }

    public static Ledger open() {
        return new Ledger(HOME);
    }

    @Override
    public void close() {
        db.close();
        factory.close();
        try {
            lock.release();
            lockFile.close();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
