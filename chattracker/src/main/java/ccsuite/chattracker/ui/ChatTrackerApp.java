package ccsuite.chattracker.ui;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import ccsuite.chattracker.db.DatabaseService;

public class ChatTrackerApp extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        // Initialize database
        DatabaseService.get();

        var fxmlUrl = getClass().getResource("main-view.fxml");
        if (fxmlUrl == null) {
            throw new RuntimeException("Cannot find main-view.fxml");
        }

        var loader = new FXMLLoader(fxmlUrl);
        Parent root = loader.load();

        var scene = new Scene(root, 900, 600);
        primaryStage.setTitle("CCAPI Chat Tracker");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(600);
        primaryStage.setMinHeight(400);
        primaryStage.show();
    }

    @Override
    public void stop() {
        DatabaseService.get().shutdown();
    }
}
