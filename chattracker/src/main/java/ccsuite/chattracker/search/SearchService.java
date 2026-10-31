package ccsuite.chattracker.search;

import com.arcadedb.database.Database;
import ccsuite.chattracker.db.DatabaseService;
import ccsuite.chattracker.db.model.IndexedConversation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SearchService {

    /**
     * Full-text search across all conversations.
     */
    public List<IndexedConversation> searchAll(String query) {
        var db = DatabaseService.get().getDatabase();
        return IndexedConversation.fullTextSearch(db, query);
    }

    /**
     * Full-text search within a specific account.
     */
    public List<IndexedConversation> searchByAccount(String query, String accountId) {
        var db = DatabaseService.get().getDatabase();
        return IndexedConversation.fullTextSearchByAccount(db, query, accountId);
    }

    /**
     * List all conversations for a specific account.
     */
    public List<IndexedConversation> listByAccount(String accountId) {
        var db = DatabaseService.get().getDatabase();
        return IndexedConversation.findByAccount(db, accountId);
    }

    /**
     * List all conversations grouped by account.
     * @return map of accountId -> conversations, ordered by accountId
     */
    public Map<String, List<IndexedConversation>> listAllGroupedByAccount() {
        var db = DatabaseService.get().getDatabase();
        var all = IndexedConversation.findAll(db);

        var grouped = new LinkedHashMap<String, List<IndexedConversation>>();
        for (var conv : all) {
            grouped.computeIfAbsent(conv.accountId(), k -> new java.util.ArrayList<>())
                .add(conv);
        }
        return grouped;
    }

    /**
     * Search with optional account filter.
     */
    public List<IndexedConversation> search(String query, String accountId) {
        if (query == null || query.isBlank()) {
            if (accountId != null && !accountId.isBlank()) {
                return listByAccount(accountId);
            }
            var db = DatabaseService.get().getDatabase();
            return IndexedConversation.findAll(db);
        }

        if (accountId != null && !accountId.isBlank()) {
            return searchByAccount(query, accountId);
        }
        return searchAll(query);
    }
}
