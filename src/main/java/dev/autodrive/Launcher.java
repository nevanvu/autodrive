package dev.autodrive;

import javafx.application.Application;

/**
 * Entry point for the fat jar. JavaFX refuses to start a main class that extends
 * Application when it's loaded from the classpath, so this plain class launches it.
 */
public final class Launcher {
    public static void main(String[] args) {
        Application.launch(App.class, args);
    }
}
