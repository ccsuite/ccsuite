package ccsuite.chattracker.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;
import ccsuite.chattracker.db.model.IndexedConversation;
import ccsuite.chattracker.search.SearchService;
import ccsuite.chattracker.sync.ConversationSyncService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class MainController {

    @FXML private TextField searchField;
    @FXML private ComboBox<String> accountFilter;
    @FXML private Button syncButton;
    @FXML private Label statusLabel;
    @FXML private TreeView<Object> conversationTree;
    @FXML private Label detailTitle;
    @FXML private Label detailAccount;
    @FXML private Label detailModel;
    @FXML private Label detailCreated;
    @FXML private Label detailUpdated;
    @FXML private Hyperlink detailLink;
    @FXML private Text detailSummary;
    @FXML private Label detailStarred;
    @FXML private VBox messagesContainer;
    @FXML private ScrollPane detailScrollPane;

    private final SearchService searchService = new SearchService();
    private final ConversationSyncService syncService = new ConversationSyncService();
    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(300));

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern ROLE_PATTERN = Pattern.compile("^\\[(human|assistant|unknown)\\]$", Pattern.MULTILINE);

    private String currentChatUrl;

    @FXML
    public void initialize() {
        searchDebounce.setOnFinished(e -> performSearch());
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            searchDebounce.stop();
            searchDebounce.playFromStart();
        });

        accountFilter.valueProperty().addListener((obs, oldVal, newVal) -> performSearch());

        conversationTree.getSelectionModel().selectedItemProperty().addListener(
            (obs, oldVal, newVal) -> {
                if (newVal != null && newVal.getValue() instanceof IndexedConversation conv) {
                    showDetail(conv);
                }
            }
        );

        var root = new TreeItem<Object>("Root");
        root.setExpanded(true);
        conversationTree.setRoot(root);

        conversationTree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else if (item instanceof String s) {
                    setText(s);
                    setStyle("-fx-font-weight: bold; -fx-font-size: 13;");
                } else if (item instanceof IndexedConversation conv) {
                    var name = conv.name() != null ? conv.name() : "(untitled)";
                    var date = formatEpoch(conv.updatedAt());
                    setText(name + "  " + date);
                    setStyle("-fx-font-size: 12;");
                }
            }
        });

        detailLink.setOnAction(e -> {
            if (currentChatUrl != null) {
                try {
                    var desktop = java.awt.Desktop.getDesktop();
                    desktop.browse(new java.net.URI(currentChatUrl));
                } catch (Exception ex) {
                    statusLabel.setText("Failed to open link: " + ex.getMessage());
                }
            }
        });

        refreshTree();
    }

    @FXML
    public void onSyncClicked() {
        syncButton.setDisable(true);
        statusLabel.setText("Syncing...");

        var thread = new Thread(() -> {
            syncService.syncAll(msg -> Platform.runLater(() -> statusLabel.setText(msg)));
            Platform.runLater(() -> {
                syncButton.setDisable(false);
                refreshTree();
                populateAccountFilter();
            });
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void performSearch() {
        var query = searchField.getText();
        var account = accountFilter.getValue();
        String accountId = null;
        if (account != null && !"All Accounts".equals(account)) {
            var parenIdx = account.lastIndexOf('(');
            if (parenIdx >= 0 && account.endsWith(")")) {
                accountId = account.substring(parenIdx + 1, account.length() - 1);
            } else {
                accountId = account;
            }
        }

        var results = searchService.search(query, accountId);
        displaySearchResults(results);
    }

    private void refreshTree() {
        var grouped = searchService.listAllGroupedByAccount();
        populateTree(grouped);
        populateAccountFilter();
    }

    private void populateTree(Map<String, List<IndexedConversation>> grouped) {
        var root = conversationTree.getRoot();
        root.getChildren().clear();

        for (var entry : grouped.entrySet()) {
            var accountId = entry.getKey();
            var conversations = entry.getValue();

            var accountName = conversations.isEmpty() ? accountId
                : conversations.getFirst().accountName();
            var displayName = accountName != null ? accountName : accountId;

            var accountNode = new TreeItem<Object>(
                displayName + " (" + conversations.size() + ")");
            accountNode.setExpanded(true);

            for (var conv : conversations) {
                accountNode.getChildren().add(new TreeItem<>(conv));
            }

            root.getChildren().add(accountNode);
        }
    }

    private void displaySearchResults(List<IndexedConversation> results) {
        var root = conversationTree.getRoot();
        root.getChildren().clear();

        if (results.isEmpty()) {
            root.getChildren().add(new TreeItem<>("No results found"));
            return;
        }

        var resultsNode = new TreeItem<Object>("Results (" + results.size() + ")");
        resultsNode.setExpanded(true);

        for (var conv : results) {
            resultsNode.getChildren().add(new TreeItem<>(conv));
        }

        root.getChildren().add(resultsNode);
    }

    private void populateAccountFilter() {
        var current = accountFilter.getValue();
        accountFilter.getItems().clear();
        accountFilter.getItems().add("All Accounts");

        var grouped = searchService.listAllGroupedByAccount();
        for (var entry : grouped.entrySet()) {
            var accountId = entry.getKey();
            var convs = entry.getValue();
            var accountName = convs.isEmpty() ? accountId : convs.getFirst().accountName();
            var display = (accountName != null ? accountName : accountId) + " (" + accountId + ")";
            accountFilter.getItems().add(display);
        }

        if (current != null && accountFilter.getItems().contains(current)) {
            accountFilter.setValue(current);
        }
    }

    private void showDetail(IndexedConversation conv) {
        detailTitle.setText(conv.name() != null ? conv.name() : "(untitled)");
        detailAccount.setText("Account: " + (conv.accountName() != null ? conv.accountName() : conv.accountId()));
        detailModel.setText("Model: " + (conv.model() != null ? conv.model() : "unknown"));
        detailCreated.setText("Created: " + formatEpoch(conv.createdAt()));
        detailUpdated.setText("Updated: " + formatEpoch(conv.updatedAt()));
        detailSummary.setText(conv.summary() != null ? conv.summary() : "(no summary)");
        detailStarred.setText(Boolean.TRUE.equals(conv.starred()) ? "Starred" : "");

        // Chat link
        if (conv.baseUrl() != null && conv.conversationUuid() != null) {
            currentChatUrl = conv.baseUrl() + "/chat/" + conv.conversationUuid();
            detailLink.setText("Open in browser");
            detailLink.setVisible(true);
        } else {
            currentChatUrl = null;
            detailLink.setVisible(false);
        }

        // Render messages
        renderMessages(conv.fullText());
        detailScrollPane.setVvalue(0);
    }

    private void renderMessages(String fullText) {
        messagesContainer.getChildren().clear();

        if (fullText == null || fullText.isBlank()) {
            var empty = new Label("(no content)");
            empty.setStyle("-fx-text-fill: #888;");
            messagesContainer.getChildren().add(empty);
            return;
        }

        // Split by role markers [human] or [assistant]
        var parts = ROLE_PATTERN.split(fullText);
        var matcher = ROLE_PATTERN.matcher(fullText);

        // Collect roles
        var roles = new java.util.ArrayList<String>();
        while (matcher.find()) {
            roles.add(matcher.group(1));
        }

        // If no role markers found (old data), show as plain text
        if (roles.isEmpty()) {
            var text = new Text(fullText);
            text.setStyle("-fx-font-size: 12;");
            var flow = new TextFlow(text);
            flow.setPadding(new Insets(8));
            flow.setStyle("-fx-background-color: #f5f5f5; -fx-background-radius: 6;");
            messagesContainer.getChildren().add(flow);
            return;
        }

        // Render each message with role styling
        for (int i = 0; i < roles.size(); i++) {
            var role = roles.get(i);
            // parts[0] is text before first marker (usually empty), parts[i+1] is text after marker i
            var msgText = (i + 1 < parts.length) ? parts[i + 1].strip() : "";
            if (msgText.isEmpty()) continue;

            var isHuman = "human".equals(role);
            var roleLabel = new Text(isHuman ? "You" : "Assistant");
            roleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 11; -fx-fill: " +
                (isHuman ? "#333;" : "#1a6baf;"));

            var content = new Text("\n" + msgText);
            content.setStyle("-fx-font-size: 12;");

            var flow = new TextFlow(roleLabel, content);
            flow.setPadding(new Insets(8, 10, 8, 10));
            flow.setStyle("-fx-background-color: " +
                (isHuman ? "#f0f0f0;" : "#e8f0fe;") +
                " -fx-background-radius: 6;");
            flow.setMaxWidth(Double.MAX_VALUE);

            messagesContainer.getChildren().add(flow);
        }
    }

    private String formatEpoch(Long epochMillis) {
        if (epochMillis == null) return "";
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
            .format(DTF);
    }
}
