package ccsuite.chattracker.sync;

import com.arcadedb.database.Database;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import ccsuite.webclient.CCAPIClient;
import ccsuite.webclient.WebHttp;
import ccsuite.base.config.CredentialsManager;
import ccsuite.chattracker.db.DatabaseService;
import ccsuite.chattracker.db.model.IndexedConversation;
import ccsuite.chatdownloader.model.ChatMessage;
import ccsuite.chatdownloader.model.ConversationDetail;
import ccsuite.base.model.CCAPICredential;
import ccsuite.webclient.model.Conversation;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ConversationSyncService {

    private static final long RATE_LIMIT_DELAY_MS = 500;
    private static final String DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    private final CCAPIClient client = new CCAPIClient();
    private final OkHttpClient httpClient = WebHttp.client();
    private final ObjectMapper mapper;

    public ConversationSyncService() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Sync conversations from all trackChats-enabled credentials.
     * @param statusCallback receives progress messages (can be null)
     */
    public void syncAll(Consumer<String> statusCallback) {
        var status = statusCallback != null ? statusCallback : (Consumer<String>) s -> {};

        try {
            var credsMgr = new CredentialsManager();
            var creds = credsMgr.load();
            var trackChatsCredentials = creds.getTrackChatsCredentials();

            if (trackChatsCredentials.isEmpty()) {
                status.accept("No credentials with trackChats=true found");
                return;
            }

            status.accept("Syncing " + trackChatsCredentials.size() + " account(s)...");
            var db = DatabaseService.get().getDatabase();

            int totalSynced = 0;
            int totalSkipped = 0;
            int totalFailed = 0;

            for (var credential : trackChatsCredentials) {
                if (credential.resolvedCcapiBaseUrl() == null || credential.resolvedCcapiBaseUrl().isEmpty()) {
                    status.accept("Skipping " + credential.id() + ": no base URL");
                    totalSkipped++;
                    continue;
                }

                try {
                    status.accept("Fetching conversations for " + credential.id() + "...");
                    var conversations = client.listConversations(credential);
                    status.accept("Found " + conversations.size() + " conversations for " + credential.id());

                    int synced = syncAccountConversations(db, credential, conversations, status);
                    totalSynced += synced;
                } catch (Exception e) {
                    status.accept("Error syncing " + credential.id() + ": " + e.getMessage());
                    totalFailed++;
                }
            }

            status.accept("Sync complete: " + totalSynced + " synced, "
                + totalSkipped + " skipped, " + totalFailed + " failed");
        } catch (Exception e) {
            status.accept("Sync failed: " + e.getMessage());
        }
    }

    private int syncAccountConversations(Database db, CCAPICredential credential,
                                          List<Conversation> conversations, Consumer<String> status) {
        int synced = 0;
        int total = conversations.size();
        int current = 0;

        for (var conv : conversations) {
            current++;
            try {
                var existing = IndexedConversation.findByUuid(db, conv.uuid());

                // Incremental: skip if already synced and not updated
                if (existing != null && existing.updatedAt() != null && conv.updatedAt() != null) {
                    long existingUpdated = existing.updatedAt();
                    long apiUpdated = conv.updatedAt().toInstant().toEpochMilli();
                    if (existingUpdated >= apiUpdated) {
                        continue;
                    }
                }

                // Fetch full conversation detail with messages
                status.accept("[" + credential.id() + " " + current + "/" + total + "] " + truncate(conv.name(), 40));
                var detail = fetchDetailedConversation(credential, conv.uuid());

                // Build full text from all messages
                var fullText = buildFullText(detail);

                // Create/update indexed conversation
                var indexed = new IndexedConversation()
                    .accountId(credential.id())
                    .accountName(credential.name())
                    .conversationUuid(conv.uuid())
                    .name(conv.name())
                    .summary(conv.summary())
                    .model(conv.model())
                    .createdAt(toEpochMillis(conv.createdAt()))
                    .updatedAt(toEpochMillis(conv.updatedAt()))
                    .starred(conv.isStarred())
                    .fullText(fullText)
                    .baseUrl(ensureHttpsPrefix(credential.resolvedCcapiBaseUrl()))
                    .lastSyncedAt(System.currentTimeMillis());

                indexed.upsertInTransaction(db);
                synced++;

                // Rate limit for free accounts
                Thread.sleep(RATE_LIMIT_DELAY_MS);

            } catch (Exception e) {
                status.accept("  Error syncing conversation " + conv.uuid() + ": " + e.getMessage());
            }
        }

        return synced;
    }

    private ConversationDetail fetchDetailedConversation(CCAPICredential credential,
                                                          String conversationId) throws IOException {
        var baseUrl = ensureHttpsPrefix(credential.resolvedCcapiBaseUrl());
        var url = String.format("%s/api/organizations/%s/chat_conversations/%s",
            baseUrl, credential.orgId(), conversationId);

        var request = new Request.Builder()
            .url(url)
            .addHeader("Cookie", "sessionKey=" + credential.key())
            .addHeader("User-Agent", credential.ua() != null ? credential.ua() : DEFAULT_UA)
            .addHeader("Accept", "*/*")
            .addHeader("Origin", baseUrl)
            .addHeader("Referer", baseUrl + "/")
            .get()
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to fetch conversation: HTTP " + response.code());
            }

            var responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Empty response body");
            }

            return mapper.readValue(responseBody.string(), ConversationDetail.class);
        }
    }

    private String buildFullText(ConversationDetail detail) {
        if (detail == null || detail.getChatMessages() == null) {
            return "";
        }
        var sb = new StringBuilder();
        for (var msg : detail.getChatMessages()) {
            if (msg.getText() == null) continue;
            var role = msg.getSender() != null ? msg.getSender() : "unknown";
            sb.append("[").append(role).append("]\n");
            sb.append(msg.getText()).append("\n\n");
        }
        return sb.toString();
    }

    private static Long toEpochMillis(OffsetDateTime odt) {
        return odt != null ? odt.toInstant().toEpochMilli() : null;
    }

    private static String ensureHttpsPrefix(String url) {
        if (url == null || url.isEmpty()) return url;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "https://" + url;
        }
        return url;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "(untitled)";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
