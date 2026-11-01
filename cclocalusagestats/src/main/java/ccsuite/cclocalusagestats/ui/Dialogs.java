package ccsuite.cclocalusagestats.ui;

import java.util.Optional;
import javafx.scene.control.*;

final class Dialogs {

    private Dialogs() {}

    /** Asks yes or no; No is the default button, so a stray Enter never deletes anything. */
    static boolean confirm(String text) {
        var a = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.YES, ButtonType.NO);
        a.setHeaderText("Delete from the ledger?");
        ((Button) a.getDialogPane().lookupButton(ButtonType.YES)).setDefaultButton(false);
        ((Button) a.getDialogPane().lookupButton(ButtonType.NO)).setDefaultButton(true);
        return a.showAndWait().filter(b -> b == ButtonType.YES).isPresent();
    }

    static Optional<String> ask(String text) {
        var d = new TextInputDialog();
        d.setHeaderText(text);
        return d.showAndWait().map(String::trim).filter(s -> !s.isEmpty());
    }
}
