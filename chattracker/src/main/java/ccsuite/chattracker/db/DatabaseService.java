package ccsuite.chattracker.db;

import com.arcadedb.ContextConfiguration;
import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.engine.ComponentFile;
import com.arcadedb.server.ArcadeDBServer;
import datapotter.arcadedbhelper.InitDoc;
import ccsuite.chattracker.db.model.IndexedConversation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public class DatabaseService {

    private static final String DB_NAME = "chat_tracker_db";
    private static final String DEFAULT_ROOT_PASSWORD = "admin12345";

    private static DatabaseService instance;
    private ArcadeDBServer server;
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);

    private DatabaseService() {
        init();
    }

    public static synchronized DatabaseService get() {
        if (instance == null) {
            instance = new DatabaseService();
        }
        return instance;
    }

    private void init() {
        if (isInitialized.get()) {
            return;
        }

        try {
            var dbDir = Path.of(System.getProperty("user.home"), "xyz-jphil", "ccapis", "chat_tracker", "database");
            Files.createDirectories(dbDir);

            GlobalConfiguration.SERVER_ROOT_PATH.setValue(dbDir.toString());
            GlobalConfiguration.PROFILE.setValue("low-ram");
            GlobalConfiguration.SERVER_ROOT_PASSWORD.setValue(DEFAULT_ROOT_PASSWORD);
            GlobalConfiguration.SERVER_METRICS.setValue(false);

            server = new ArcadeDBServer(new ContextConfiguration());
            server.start();

            if (!server.existsDatabase(DB_NAME)) {
                server.createDatabase(DB_NAME, ComponentFile.MODE.READ_WRITE);
            }

            var db = server.getDatabase(DB_NAME);
            initSchema(db);

            isInitialized.set(true);
        } catch (Exception e) {
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    private void initSchema(Database db) {
        InitDoc.initDocTypes(db, IndexedConversation.TYPEDEF);
    }

    public Database getDatabase() {
        if (!isInitialized.get()) {
            throw new IllegalStateException("Database not initialized");
        }
        return server.getDatabase(DB_NAME);
    }

    public synchronized void shutdown() {
        if (!isInitialized.getAndSet(false)) {
            return;
        }
        if (server != null && server.isStarted()) {
            try {
                server.stop();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
