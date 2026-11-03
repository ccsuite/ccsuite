package ccsuite.chatdownloader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import ccsuite.webclient.CCAPIClient;
import ccsuite.base.config.CredentialsManager;
import ccsuite.chatdownloader.model.ChatMessage;
import ccsuite.chatdownloader.model.ConversationDetail;
import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.CCAPIsCredentials;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static luvml.E.*;
import static luvml.A.*;
import static luvml.Frags.*;
import static luvml.ProcessingInstruction.xmlDeclaration;
import static ccsuite.chatdownloader.ConversationDSL.*;
import luvml.DocType;
import luvml.o.XHtmlStringRenderer;

/**
 * CLI tool to download conversation history from Claude Compatible APIs
 *
 * Usage:
 *   java -jar conversation-downloader.jar <url> [options]
 *
 * Parameters:
 *   <url>            - Conversation URL in format: https://example.com/chat/<conversation-id>
 *   --account <id>   - (Optional) Account ID from CCAPIsCredentials.xml to use
 *   --name <name>    - (Optional) Custom name for saved file (defaults to conversation ID)
 *   --output <dir>   - (Optional) Output directory (defaults to current directory)
 */
public class ConversationDownloaderCLI {
    static final CCAPIClient CLIENT = new CCAPIClient();

    private static final Pattern URL_PATTERN = Pattern.compile(".*?/chat/([a-zA-Z0-9-]+)");

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        try {
            var options = parseArguments(args);
            downloadConversation(options);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("Conversation Downloader CLI");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar conversation-downloader.jar <url> [options]");
        System.out.println();
        System.out.println("Parameters:");
        System.out.println("  <url>            Conversation URL (e.g., https://example.com/chat/abc-123)");
        System.out.println("  --account <id>   Account ID from CCAPIsCredentials.xml (optional)");
        System.out.println("  --name <name>    Custom name for saved file (optional, defaults to conversation ID)");
        System.out.println("  --output <dir>   Output directory (optional, defaults to current directory)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar conversation-downloader.jar https://example.com/chat/abc-123");
        System.out.println("  java -jar conversation-downloader.jar https://example.com/chat/abc-123 --name my-conversation");
        System.out.println("  java -jar conversation-downloader.jar https://example.com/chat/abc-123 --account work --output ./downloads");
    }

    private static DownloadOptions parseArguments(String[] args) {
        var options = new DownloadOptions();
        options.url = args[0];

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--account":
                    if (i + 1 < args.length) {
                        options.accountId = args[++i];
                    }
                    break;
                case "--name":
                    if (i + 1 < args.length) {
                        options.customName = args[++i];
                    }
                    break;
                case "--output":
                    if (i + 1 < args.length) {
                        options.outputDir = args[++i];
                    }
                    break;
            }
        }

        return options;
    }

    private static void downloadConversation(DownloadOptions options) throws Exception {
        // Extract conversation ID from URL
        var conversationId = extractConversationId(options.url);
        System.out.println("Conversation ID: " + conversationId);

        // Load credentials
        var credentialsManager = new CredentialsManager();
        if (!credentialsManager.credentialsFileExists()) {
            throw new IOException("Credentials file not found at: " + credentialsManager.getCredentialsPath());
        }

        CCAPIsCredentials credentials = credentialsManager.load();
        System.out.println("Loaded credentials from: " + credentialsManager.getCredentialsPath());

        // Get credentials to try
        List<CCAPICredential> credentialsToTry;
        if (options.accountId != null) {
            // Use specific account
            var credential = findCredentialById(credentials, options.accountId);
            if (credential == null) {
                throw new IllegalArgumentException("Account ID '" + options.accountId + "' not found in credentials");
            }
            credentialsToTry = List.of(credential);
            System.out.println("Using account: " + credential.id() + " (" + credential.name() + ")");
        } else {
            // Try all credentials (both active and inactive)
            credentialsToTry = credentials.credentials();
            System.out.println("Trying all " + credentialsToTry.size() + " credentials (active and inactive)...");
        }

        // Try fetching conversation with each credential
        ConversationDetail conversation = null;
        CCAPICredential successfulCredential = null;

        for (var credential : credentialsToTry) {
            try {
                System.out.println("Trying credential: " + credential.id() + " (" + credential.name() + ")");
                conversation = fetchDetailedConversation(credential, conversationId);
                successfulCredential = credential;
                System.out.println("✓ Successfully fetched conversation using: " + credential.id());
                break;
            } catch (Exception e) {
                System.out.println("✗ Failed with credential " + credential.id() + ": " + e.getMessage());
            }
        }

        if (conversation == null) {
            throw new IOException("Failed to fetch conversation with any available credentials");
        }

        // Save conversation to files (both JSON and XHTML)
        var baseName = options.customName != null ? options.customName : conversationId;
        var outputDir = options.outputDir != null ? options.outputDir : ".";

        var jsonPath = Paths.get(outputDir, baseName + ".json");
        var xhtmlPath = Paths.get(outputDir, baseName + ".conversation.xhtml");

        saveConversationAsJson(conversation, jsonPath);
        saveConversationAsXHtml(conversation, xhtmlPath, successfulCredential, conversationId);

        System.out.println();
        System.out.println("Conversation saved:");
        System.out.println("  JSON: " + jsonPath.toAbsolutePath());
        System.out.println("  XHTML: " + xhtmlPath.toAbsolutePath());
        System.out.println();
        System.out.println("Conversation: " + conversation.getName());
        System.out.println("Messages: " + (conversation.getChatMessages() != null ? conversation.getChatMessages().size() : 0));
        System.out.println("Created: " + conversation.getCreatedAt());
    }

    private static String extractConversationId(String url) {
        Matcher matcher = URL_PATTERN.matcher(url);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        throw new IllegalArgumentException("Invalid conversation URL format. Expected: .../chat/<conversation-id>");
    }

    private static CCAPICredential findCredentialById(CCAPIsCredentials credentials, String id) {
        return credentials.credentials().stream()
                .filter(c -> c.id().equalsIgnoreCase(id))
                .findFirst()
                .orElse(null);
    }

    private static ConversationDetail fetchDetailedConversation(CCAPICredential credential, String conversationId) throws IOException {
        var mapper = new ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return mapper.readValue(CLIENT.getConversationJson(credential, conversationId), ConversationDetail.class);
    }


    private static void saveConversationAsJson(ConversationDetail conversation, Path outputPath) throws IOException {
        // Ensure output directory exists
        var parentDir = outputPath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }

        // Write pretty-printed JSON
        var mapper = new ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(outputPath.toFile(), conversation);
    }

    private static void saveConversationAsXHtml(ConversationDetail conversation, Path outputPath,
                                                 CCAPICredential credential, String conversationId) throws IOException {
        // Ensure output directory exists
        var parentDir = outputPath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }

        // Build metadata section
        var metadataSection = metadata(
            h2(text("Conversation Metadata")),
            div(text("UUID: " + conversation.getUuid())),
            div(text("Name: " + conversation.getName())),
            if_(conversation.getSummary() != null && !conversation.getSummary().isEmpty(), () ->
                div(text("Summary: " + conversation.getSummary()))),
            div(text("Created: " + conversation.getCreatedAt())),
            div(text("Updated: " + conversation.getUpdatedAt())),
            div(text("Organization ID: " + credential.orgId())),
            div(text("Account: " + credential.id() + " (" + credential.name() + ")")),
            div(text("View online: "),
                a(href("https://claude.ai/chat/" + conversationId), text("https://claude.ai/chat/" + conversationId)))
        );

        // Build messages
        var messageFrags = frags();
        if (conversation.getChatMessages() != null) {
            for (int i = 0; i < conversation.getChatMessages().size(); i++) {
                var message = conversation.getChatMessages().get(i);
                var role = message.getSender() != null ? message.getSender() : "unknown";
                var serialNum = i + 1;

                // Create custom element for message (human or assistant)
                if ("human".equals(role)) {
                    messageFrags.____(
                        human(
                            serialNum(serialNum),
                            if_(message.getUuid() != null, () -> uuid(message.getUuid())),
                            if_(message.getCreatedAt() != null, () -> created_at(message.getCreatedAt())),
                            // Raw markdown text - preserved as-is
                            text(message.getText() != null ? message.getText() : "")
                        )
                    );
                } else if ("assistant".equals(role)) {
                    messageFrags.____(
                        assistant(
                            serialNum(serialNum),
                            if_(message.getUuid() != null, () -> uuid(message.getUuid())),
                            if_(message.getCreatedAt() != null, () -> created_at(message.getCreatedAt())),
                            // Raw markdown text - preserved as-is
                            text(message.getText() != null ? message.getText() : "")
                        )
                    );
                }
            }
        }

        var messagesSection = messages(messageFrags);

        // Build complete XHTML document
        var xhtmlDoc = frags(
            xmlDeclaration("UTF-8"),
            DocType.html5(),
            html(xmlns("http://www.w3.org/1999/xhtml"),
                head(
                    meta(charset("UTF-8")),
                    meta(name("viewport"), content("width=device-width, initial-scale=1.0")),
                    meta(name("description"), content(ConversationSemanticXHtml.DEFINITION)),
                    meta(name("trivia"), content(ConversationSemanticXHtml.TRIVIA)),
                    meta(name("date"), content(LocalDateTime.now().atZone(ZoneId.systemDefault())
                        .withZoneSameInstant(ZoneOffset.UTC).toString())),
                    title(conversation.getName() + " (" + ConversationSemanticXHtml.TYPE + ")"),
                    link(rel("stylesheet"), href(ConversationSemanticXHtml.CSS)),
                    script(src(ConversationSemanticXHtml.JS))
                ),
                body(
                    // Define custom elements as block-level
                    style("human, assistant, metadata, messages { display: block; }"),
                    h1(text(conversation.getName())),
                    metadataSection,
                    messagesSection
                )
            )
        );

        // Render and save
        var xhtmlContent = XHtmlStringRenderer.asFormatted(xhtmlDoc, "");
        Files.writeString(outputPath, xhtmlContent, StandardCharsets.UTF_8);
    }

    private static class DownloadOptions {
        String url;
        String accountId;
        String customName;
        String outputDir;
    }
}
