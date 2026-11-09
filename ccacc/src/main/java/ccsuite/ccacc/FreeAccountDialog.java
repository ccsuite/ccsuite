package ccsuite.ccacc;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;

import java.io.IOException;
import java.util.Optional;

/**
 * Adds a free account: short name, e-mail, organisation id and web session key. The dialog stays open with the store's own message when a value is refused
 * ({@link Accounts#addFree}), so nothing typed is lost; the key is visible in its field and appears in no message. Returns the new account's id, or empty when cancelled.
 */
final class FreeAccountDialog {

    static Optional<String> show(Accounts accounts) {
        var dialog = new Dialog<String>();
        dialog.setTitle("Add free account");
        var name = new TextField(); var mail = new TextField(); var org = new TextField(); var key = new TextField();
        name.setPromptText("short, e.g. QX");
        org.setPromptText("the claude.ai organisation id");
        key.setPromptText("the web session key");
        var added = new String[1];
        var message = new Label();
        message.setWrapText(true);
        message.setMaxWidth(420);
        message.setStyle("-fx-text-fill: #b3261e;");
        var grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(7);
        grid.setPadding(new Insets(12));
        grid.addRow(0, new Label("Short name"), name);
        grid.addRow(1, new Label("E-mail of the account"), mail);
        grid.addRow(2, new Label("Organisation id"), org);
        grid.addRow(3, new Label("Session key"), key);
        grid.add(message, 0, 4, 2, 1);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            try {
                accounts.addFree(name.getText(), mail.getText(), org.getText(), key.getText());
                added[0] = name.getText().trim();
            } catch (IllegalArgumentException | IOException ex) {
                message.setText(ex.getMessage());
                e.consume();
            }
        });
        dialog.setResultConverter(b -> b == ButtonType.OK ? added[0] : null);
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    private FreeAccountDialog() {}
}
