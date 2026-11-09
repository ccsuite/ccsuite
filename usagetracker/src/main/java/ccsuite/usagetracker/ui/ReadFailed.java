package ccsuite.usagetracker.ui;

import javafx.application.Platform;
import javafx.scene.control.Alert;

/** The one answer to a failed "read this account again now" (the card's tier mark, a row's button): a dialog with the failure in the server's own words. Silence means it worked. Any thread. */
public final class ReadFailed {

    public static void show(String id, String failure) {
        Platform.runLater(() -> {
            var alert = new Alert(Alert.AlertType.ERROR, failure);
            alert.setTitle("ccacc");
            alert.setHeaderText("Reading " + id + " failed");
            alert.show();
        });
    }

    private ReadFailed() {}
}
