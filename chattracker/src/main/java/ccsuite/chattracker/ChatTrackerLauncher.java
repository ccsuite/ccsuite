package ccsuite.chattracker;

import javafx.application.Application;
import ccsuite.chattracker.ui.ChatTrackerApp;

/**
 * Non-JavaFX entry point for maven-shade executable JAR.
 * Required because JavaFX Application subclasses cannot be the main class
 * in a shaded JAR without the JavaFX module system.
 */
public class ChatTrackerLauncher {

    public static void main(String[] args) {
        Application.launch(ChatTrackerApp.class, args);
    }
}
