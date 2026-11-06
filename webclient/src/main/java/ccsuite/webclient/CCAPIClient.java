package ccsuite.webclient;

import okhttp3.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ccsuite.base.model.*;
import ccsuite.webclient.model.*;
import ccsuite.webclient.streaming.StreamingEventParser;
import ccsuite.tokencounter.api.TokenizerService;
import ccsuite.tokencounter.api.TokenizationResult;
import ccsuite.tokencounter.api.TokenizerException;
import ccsuite.tokencounter.providers.freshfriedfish.FreshFriedFishTokenizerService;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Simplified API client for CCAPI (Claude Compatible API).
 * Now works with shared CCAPICredential objects from base module.
 * Each credential contains its own ccapiBaseUrl, so no global Settings needed.
 */
public class CCAPIClient {

    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.75 Safari/537.36";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TokenizerService tokenCounter;

    /**
     * Create client (stateless - credentials passed to each method)
     */
    public CCAPIClient() {
        this(null);
    }

    public CCAPIClient(Interceptor interceptor) {
        this.httpClient = WebHttp.client(interceptor);
        this.objectMapper = createObjectMapper();
        this.tokenCounter = new FreshFriedFishTokenizerService();
    }

    private ObjectMapper createObjectMapper() {
        var mapper = new ObjectMapper();
        // Register Java 8 time module for OffsetDateTime support
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        // Disable failing on unknown properties to be more flexible with API changes
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    private String ensureHttpsPrefix(String url) {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("Base URL cannot be null or empty");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "https://" + url;
        }
        return url;
    }

    /** The completion body. The model goes here, not into the chat: a chat created with a model is still answered by the account's default one. */
    static java.util.Map<String, String> payload(String message, String model) {
        var payload = new java.util.LinkedHashMap<String, String>();
        payload.put("prompt", message);
        payload.put("timezone", "UTC");
        if (model != null && !model.isBlank()) payload.put("model", model);
        return payload;
    }

    private String getUserAgent(CCAPICredential credential) {
        return (credential.ua() != null && !credential.ua().isEmpty())
                ? credential.ua()
                : DEFAULT_USER_AGENT;
    }

    private String getBaseUrl(CCAPICredential credential) {
        return ensureHttpsPrefix(credential.resolvedCcapiBaseUrl());
    }

    /**
     * Fetch usage statistics for a credential
     */
    public UsageData fetchUsage(CCAPICredential credential) throws IOException {
        var baseUrl = getBaseUrl(credential);
        var url = String.format("%s/api/organizations/%s/usage", baseUrl, credential.orgId());

        var request = new Request.Builder()
                .url(url)
                .addHeader("Cookie", "sessionKey=" + credential.key())
                .addHeader("User-Agent", getUserAgent(credential))
                .addHeader("Accept", "*/*")
                .addHeader("Origin", baseUrl)
                .addHeader("Referer", baseUrl + "/")
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to fetch usage: HTTP " + response.code());
            }

            var responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Empty response body");
            }

            return objectMapper.readValue(responseBody.string(), UsageData.class);
        }
    }

    /**
     * List all conversations for a credential
     */
    public List<Conversation> listConversations(CCAPICredential credential) throws IOException {
        var baseUrl = getBaseUrl(credential);
        var url = String.format("%s/api/organizations/%s/chat_conversations", baseUrl, credential.orgId());

        var request = new Request.Builder()
                .url(url)
                .addHeader("Cookie", "sessionKey=" + credential.key())
                .addHeader("User-Agent", getUserAgent(credential))
                .addHeader("Accept", "*/*")
                .addHeader("Origin", baseUrl)
                .addHeader("Referer", baseUrl + "/")
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to list conversations: HTTP " + response.code());
            }

            var responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Empty response body");
            }

            return objectMapper.readValue(responseBody.string(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Conversation.class));
        }
    }

    /**
     * Create a new chat conversation
     *
     * @param credential the credential to use
     * @param name conversation name
     * @param isTemporary whether the conversation is temporary (controls visibility)
     * @return the created conversation
     */
    public SingleConversation createChat(CCAPICredential credential, String name, boolean isTemporary) throws IOException {
        return createChat(credential, name, isTemporary, null);
    }

    /**
     * Create a new chat conversation with a specific model
     *
     * @param credential the credential to use
     * @param name conversation name
     * @param isTemporary whether the conversation is temporary (controls visibility)
     * @param model optional model name
     * @return the created conversation
     */
    public SingleConversation createChat(CCAPICredential credential, String name, boolean isTemporary, String model) throws IOException {
        var baseUrl = getBaseUrl(credential);
        var url = String.format("%s/api/organizations/%s/chat_conversations", baseUrl, credential.orgId());

        var uuid = UUID.randomUUID().toString();

        // Build JSON payload
        String json;
        if (model != null) {
            json = String.format(
                "{\"uuid\":\"%s\",\"name\":\"%s\",\"is_temporary\":%s,\"include_conversation_preferences\":true,\"model\":\"%s\"}",
                uuid, name, isTemporary, model
            );
        } else {
            json = String.format(
                "{\"uuid\":\"%s\",\"name\":\"%s\",\"is_temporary\":%s,\"include_conversation_preferences\":true}",
                uuid, name, isTemporary
            );
        }

        var requestBody = RequestBody.create(json, MediaType.get("application/json"));

        var request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Cookie", "sessionKey=" + credential.key())
                .addHeader("User-Agent", getUserAgent(credential))
                .addHeader("Accept", "*/*")
                .addHeader("Content-Type", "application/json")
                .addHeader("Origin", baseUrl)
                .addHeader("Referer", baseUrl + "/")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                var errorBody = response.body() != null ? response.body().string() : "";
                throw new IOException("Failed to create chat: HTTP " + response.code() + " - " + errorBody);
            }

            var responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Empty response body");
            }

            return objectMapper.readValue(responseBody.string(), SingleConversation.class);
        }
    }

    /**
     * Send a message to a conversation
     * Returns the raw event stream response
     */
    public String sendMessage(CCAPICredential credential, String conversationId, String message) throws IOException {
        return sendMessage(credential, conversationId, message, null);
    }

    /** Like {@link #sendMessage(CCAPICredential, String, String)}, answered by the given model (null = the account's default). */
    public String sendMessage(CCAPICredential credential, String conversationId, String message, String model) throws IOException {
        var baseUrl = getBaseUrl(credential);
        var url = String.format("%s/api/organizations/%s/chat_conversations/%s/completion",
                baseUrl, credential.orgId(), conversationId);

        // Build JSON properly using Jackson (handles all escaping automatically)
        var payload = payload(message, model);
        var json = objectMapper.writeValueAsString(payload);

        var requestBody = RequestBody.create(json, MediaType.get("application/json"));

        var request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Cookie", "sessionKey=" + credential.key())
                .addHeader("User-Agent", getUserAgent(credential))
                .addHeader("Accept", "text/event-stream")
                .addHeader("Content-Type", "application/json")
                .addHeader("Origin", baseUrl)
                .addHeader("Referer", baseUrl + "/")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                var errorBody = response.body() != null ? response.body().string() : "";
                throw new IOException("Failed to send message: HTTP " + response.code() + " - " + errorBody);
            }

            var responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Empty response body");
            }

            return responseBody.string();
        }
    }

    /**
     * Send a message to a conversation with streaming callback support
     *
     * @param credential the credential to use
     * @param conversationId the conversation ID
     * @param message the message to send
     * @param onText callback invoked for each text chunk (can be null)
     * @return the complete response text
     * @throws IOException if the request fails
     */
    public String sendMessageStreaming(CCAPICredential credential, String conversationId,
                                      String message, Consumer<String> onText) throws IOException {
        return sendMessageStreaming(credential, conversationId, message, null, onText);
    }

    /** Like the four-argument form, answered by the given model (null = the account's default model, which is what the web chat uses when no model is sent). */
    public String sendMessageStreaming(CCAPICredential credential, String conversationId,
                                      String message, String model, Consumer<String> onText) throws IOException {
        var baseUrl = getBaseUrl(credential);
        var url = String.format("%s/api/organizations/%s/chat_conversations/%s/completion",
                baseUrl, credential.orgId(), conversationId);

        // Build JSON properly using Jackson (handles all escaping automatically)
        var payload = payload(message, model);
        var json = objectMapper.writeValueAsString(payload);

        var requestBody = RequestBody.create(json, MediaType.get("application/json"));

        var request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Cookie", "sessionKey=" + credential.key())
                .addHeader("User-Agent", getUserAgent(credential))
                .addHeader("Accept", "text/event-stream")
                .addHeader("Content-Type", "application/json")
                .addHeader("Origin", baseUrl)
                .addHeader("Referer", baseUrl + "/")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                var errorBody = response.body() != null ? response.body().string() : "";
                throw new IOException("Failed to send message: HTTP " + response.code() + " - " + errorBody);
            }

            var responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Empty response body");
            }

            // Parse the SSE stream with callbacks
            var parser = new StreamingEventParser(objectMapper);
            if (onText != null) {
                parser.onText(onText);
            }

            return parser.parse(responseBody.byteStream());
        }
    }

    /** One reply of a chat in the Messages-style stream: its text and the uuid of the assistant message (the parent of the next message of the same chat). */
    public record Reply(String text, String uuid, Gauge gauge) { }

    /** The usage gauge a Messages-style reply carries (fractions of the five-hour and weekly windows, steps of 0.01); any part may be null. */
    public record Gauge(Double fiveHour, Double sevenDay, Long fiveResetsAt) { }

    /**
     * Sends one message in the Messages-style stream ({@code rendering_mode: messages}), which names the assistant message in {@code message_start};
     * that uuid is the {@code parent_message_uuid} of the follow-up. {@code parentUuid} is null for the first message of a chat. A follow-up without
     * its parent is refused at once by the web (HTTP 429 "Rate limited"). Text arrives in {@code onText} (may be null).
     */
    public Reply sendTurnStreaming(CCAPICredential credential, String conversationId, String message, String model, String parentUuid,
                                   Consumer<String> onText) throws IOException {
        var baseUrl = getBaseUrl(credential);
        var url = String.format("%s/api/organizations/%s/chat_conversations/%s/completion", baseUrl, credential.orgId(), conversationId);
        var body = objectMapper.createObjectNode().put("prompt", message).put("timezone", "UTC").put("rendering_mode", "messages");
        if (model != null && !model.isBlank()) body.put("model", model);
        if (parentUuid != null) body.put("parent_message_uuid", parentUuid);
        var request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), MediaType.get("application/json")))
                .addHeader("Cookie", "sessionKey=" + credential.key())
                .addHeader("User-Agent", getUserAgent(credential))
                .addHeader("Accept", "text/event-stream")
                .addHeader("Content-Type", "application/json")
                .addHeader("Origin", baseUrl)
                .addHeader("Referer", baseUrl + "/")
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                var errorBody = response.body() != null ? response.body().string() : "";
                throw new IOException("Failed to send message: HTTP " + response.code() + " - " + errorBody);
            }
            if (response.body() == null) throw new IOException("Empty response body");
            return readReply(response.body().byteStream(), onText);
        }
    }

    Reply readReply(java.io.InputStream in, Consumer<String> onText) throws IOException {
        var text = new StringBuilder();
        String uuid = null;
        Gauge gauge = null;
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            for (var line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.startsWith("data:")) continue;
                var data = line.substring(5).strip();
                if (data.isEmpty() || data.equals("ping")) continue;
                com.fasterxml.jackson.databind.JsonNode event;
                try { event = objectMapper.readTree(data); } catch (IOException e) { continue; }
                switch (event.path("type").asText()) {
                    case "message_start" -> uuid = event.path("message").path("uuid").asText(null);
                    case "content_block_delta" -> {
                        var piece = event.path("delta").path("text").asText("");
                        if (!piece.isEmpty()) {
                            text.append(piece);
                            if (onText != null) onText.accept(piece);
                        }
                    }
                    case "error" -> throw new IOException("Stream error: " + event.path("error").toString());
                    case "message_limit" -> {
                        var windows = event.path("message_limit").path("windows");
                        if (!windows.isObject()) windows = event.path("windows");
                        if (windows.isObject()) gauge = new Gauge(
                            windows.path("5h").hasNonNull("utilization") ? windows.path("5h").get("utilization").asDouble() : null,
                            windows.path("7d").hasNonNull("utilization") ? windows.path("7d").get("utilization").asDouble() : null,
                            windows.path("5h").hasNonNull("resets_at") ? windows.path("5h").get("resets_at").asLong() : null);
                    }
                    default -> { }
                }
            }
        }
        return new Reply(text.toString(), uuid, gauge);
    }

    /**
     * Get a specific conversation by ID
     */
    public Conversation getConversation(CCAPICredential credential, String conversationId) throws IOException {
        return objectMapper.readValue(getConversationJson(credential, conversationId), Conversation.class);
    }

    public String getConversationJson(CCAPICredential credential, String conversationId) throws IOException {
        var baseUrl = getBaseUrl(credential);
        var url = String.format("%s/api/organizations/%s/chat_conversations/%s",
                baseUrl, credential.orgId(), conversationId);

        var request = new Request.Builder()
                .url(url)
                .addHeader("Cookie", "sessionKey=" + credential.key())
                .addHeader("User-Agent", getUserAgent(credential))
                .addHeader("Accept", "*/*")
                .addHeader("Origin", baseUrl)
                .addHeader("Referer", baseUrl + "/")
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to get conversation: HTTP " + response.code());
            }

            var responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Empty response body");
            }

            return responseBody.string();
        }
    }

    /**
     * Shutdown the HTTP client and release resources
     */
    public void shutdown() {
        httpClient.dispatcher().cancelAll();
        httpClient.connectionPool().evictAll();
        httpClient.dispatcher().executorService().shutdownNow();
    }

    /**
     * Count tokens in a message before sending
     *
     * @param message the message text to count
     * @return token count result with detailed metrics
     * @throws TokenizerException if token counting fails
     */
    public TokenizationResult countTokens(String message) throws TokenizerException {
        return tokenCounter.countTokens(message);
    }

    /**
     * Check if token counter service is available
     *
     * @return true if token counter is healthy
     */
    public boolean isTokenCounterHealthy() {
        return tokenCounter.isHealthy();
    }

    /**
     * Get the token counter service for direct access
     *
     * @return token counter service instance
     */
    public TokenizerService getTokenCounter() {
        return tokenCounter;
    }

    /**
     * Retry wrapper for any operation
     */
    public <T> T withRetry(RetryableOperation<T> operation, int maxRetries) {
        IOException lastException = null;

        for (int i = 0; i < maxRetries; i++) {
            try {
                return operation.execute();
            } catch (IOException e) {
                lastException = e;
                // Wait before retry (exponential backoff)
                try {
                    Thread.sleep((long) Math.pow(2, i) * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        throw new RuntimeException("Operation failed after " + maxRetries + " retries", lastException);
    }

    @FunctionalInterface
    public interface RetryableOperation<T> {
        T execute() throws IOException;
    }
}
