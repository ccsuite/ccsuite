package ccsuite.chattracker.db.model;

import com.arcadedb.database.Database;
import com.arcadedb.database.Document;
import com.arcadedb.query.sql.executor.ResultSet;
import datapotter.arcadedbhelper.ArcadeData;
import datapotter.arcadedbhelper.TypeDef;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@ArcadeData
public final class IndexedConversation extends IndexedConversation_A {

    String accountId;
    String accountName;
    String conversationUuid;
    String name;
    String summary;
    String model;
    Long createdAt;
    Long updatedAt;
    Boolean starred;
    String fullText;
    String baseUrl;
    Long lastSyncedAt;

    public static final TypeDef<IndexedConversation> TYPEDEF =
        schemaBuilder()
            .unique($conversationUuid)
            .lsmIndex($accountId)
            .fullStringIndex($fullText)
            .__();

    // --- Utility query methods ---

    public void upsertInTransaction(Database db) {
        db.transaction(() ->
            in(db).whereEq($conversationUuid, conversationUuid()).upsert()
        );
    }

    public static List<IndexedConversation> findByAccount(Database db, String accountId) {
        var docs = db.select()
            .fromType(IndexedConversation.__)
            .where().property($accountId.__).eq().value(accountId)
            .orderBy($updatedAt.__, false)
            .documents();
        return fromDocuments(docs);
    }

    public static List<IndexedConversation> findAll(Database db) {
        var docs = db.select()
            .fromType(IndexedConversation.__)
            .orderBy($accountId.__, true)
            .documents();
        return fromDocuments(docs);
    }

    public static IndexedConversation findByUuid(Database db, String uuid) {
        var docs = db.select()
            .fromType(IndexedConversation.__)
            .where().property($conversationUuid.__).eq().value(uuid)
            .limit(1)
            .documents();
        if (docs.hasNext()) {
            var c = new IndexedConversation();
            c.fromArcadeDocument(docs.next());
            return c;
        }
        return null;
    }

    public static List<IndexedConversation> fullTextSearch(Database db, String query) {
        var pattern = "%" + query + "%";
        var rs = db.query("sql",
            "SELECT FROM " + IndexedConversation.__ + " WHERE fullText LIKE ? OR name LIKE ? ORDER BY updatedAt DESC",
            pattern, pattern);
        return fromResultSet(rs);
    }

    public static List<IndexedConversation> fullTextSearchByAccount(Database db, String query, String accountId) {
        var pattern = "%" + query + "%";
        var rs = db.query("sql",
            "SELECT FROM " + IndexedConversation.__ + " WHERE (fullText LIKE ? OR name LIKE ?) AND accountId = ? ORDER BY updatedAt DESC",
            pattern, pattern, accountId);
        return fromResultSet(rs);
    }

    private static List<IndexedConversation> fromDocuments(Iterator<Document> docs) {
        var list = new ArrayList<IndexedConversation>();
        while (docs.hasNext()) {
            var c = new IndexedConversation();
            c.fromArcadeDocument(docs.next());
            list.add(c);
        }
        return list;
    }

    private static List<IndexedConversation> fromResultSet(ResultSet rs) {
        var list = new ArrayList<IndexedConversation>();
        while (rs.hasNext()) {
            var c = new IndexedConversation();
            c.fromArcadeDocument(rs.next().toElement());
            list.add(c);
        }
        return list;
    }
}
