package com.musicplayer.scamusica;

import com.musicplayer.scamusica.controller.CodeVerificationController;
import com.musicplayer.scamusica.controller.PlayerController;
import com.musicplayer.scamusica.manager.LanguageManager;
import com.musicplayer.scamusica.manager.SessionManager;
import com.musicplayer.scamusica.service.LogSyncService;
import com.musicplayer.scamusica.util.AppLogger;
import javafx.application.Application;
import javafx.stage.Stage;

import java.io.PrintWriter;
import java.io.StringWriter;

public class Main extends Application {

    /**
     * ✅ Converts full stack trace to a String for logging.
     */
    private static String getFullStackTrace(Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sw.toString();
    }

    @Override
    public void start(Stage primaryStage) {
        System.setProperty("java.net.useSystemProxies", "true");

        // ✅ Catch exceptions thrown on the JavaFX Application Thread (FX thread errors
        // are NOT caught by Thread.setDefaultUncaughtExceptionHandler)
        Thread.currentThread().setUncaughtExceptionHandler((thread, throwable) -> {
            AppLogger.log("[Main-FXThread] Uncaught exception on JavaFX thread: " + throwable.toString());
            AppLogger.log("[Main-FXThread] Stack Trace:\n" + getFullStackTrace(throwable));
            try {
                com.musicplayer.scamusica.service.LogSyncService.getInstance()
                        .addErrorLog(throwable.toString(), "FXThread-UncaughtException");
            } catch (Exception ignored) {}
        });

        // Set prefer language from the session
        String savedLang = SessionManager.getLanguage();
        LanguageManager.setLanguage(savedLang != null ? savedLang : "en");

        if (SessionManager.isUserLoggedIn()) {
            // User already has valid token → skip login screen
            System.out.println("Auto-login using saved token");
            new PlayerController().start(primaryStage);
        } else {
            CodeVerificationController codeVerificationController = new CodeVerificationController();
            codeVerificationController.start(primaryStage);
        }
    }

    public static void main(String[] args) {
        AppLogger.init();

        // ✅ Handler: Catches errors thrown on background/non-FX threads
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            // Always log every uncaught exception with full stack trace
            AppLogger.log("[Main] ⚠️ Uncaught Exception on thread [" + thread.getName() + "]: "
                    + throwable.toString());
            AppLogger.log("[Main] Full Stack Trace:\n" + getFullStackTrace(throwable));
            try {
                com.musicplayer.scamusica.service.LogSyncService.getInstance()
                        .addErrorLog(throwable.toString(), "Thread-" + thread.getName() + "-UncaughtException");
            } catch (Exception ignored) {}

            // Log suppressed exceptions if any
            for (Throwable suppressed : throwable.getSuppressed()) {
                AppLogger.log("[Main] Suppressed: " + suppressed.toString()
                        + "\n" + getFullStackTrace(suppressed));
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            AppLogger.log("[Main] ⚠️ JVM Shutdown Hook triggered. Application is exiting.");
        }, "ShutdownHook-Logger"));

        launch(args);
    }
}