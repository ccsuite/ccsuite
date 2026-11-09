package ccsuite.ccacc;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

/** Asks before Claude Code is moved to another account, the one way to do it from a click on an account's name (the card, the usage windows, the main window). FX thread. */
final class SwitchConfirm {

    static void ask(Parts parts, String id) {
        if (parts.watcher == null) { tell("Turn the account watcher on to switch accounts."); return; }
        if (id.equals(parts.bridge.activeId)) { tell(id + " is already the account Claude Code uses."); return; }
        var ask = new Alert(Alert.AlertType.CONFIRMATION, "Make " + id + " the account Claude Code uses?", ButtonType.OK, ButtonType.CANCEL);
        ask.setTitle("ccacc");
        ask.setHeaderText("Switch to " + id);
        if (ask.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) parts.watcher.requestSwitch(id);
    }

    static void tell(String text) {
        var a = new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK);
        a.setTitle("ccacc");
        a.setHeaderText(null);
        a.show();
    }

    private SwitchConfirm() {}
}
