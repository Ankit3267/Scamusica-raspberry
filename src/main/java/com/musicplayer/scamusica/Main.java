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
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.StageStyle;
import java.util.ResourceBundle;
import java.util.Locale;

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

        // 1. Show splash immediately
        Stage splashStage = createSplashStage();
        splashStage.show();

        Thread.currentThread().setUncaughtExceptionHandler((thread, throwable) -> {
            AppLogger.log("[Main-FXThread] Uncaught exception on JavaFX thread: " + throwable.toString());
            AppLogger.log("[Main-FXThread] Stack Trace:\n" + getFullStackTrace(throwable));
            try {
                com.musicplayer.scamusica.service.LogSyncService.getInstance()
                        .addErrorLog(throwable.toString(), "FXThread-UncaughtException");
            } catch (Exception ignored) {}
        });

        // 2. Run heavy initialization on a background thread so the splash
        //    has time to render before we proceed to the main UI.
        new Thread(() -> {
            try {
                // Set prefer language from the session
                String savedLang = SessionManager.getLanguage();
                boolean isLoggedIn = SessionManager.isUserLoggedIn();

                javafx.application.Platform.runLater(() -> {
                    try {
                        LanguageManager.setLanguage(savedLang != null ? savedLang : "en");

                        if (isLoggedIn) {
                            System.out.println("Auto-login using saved token");
                            new PlayerController().startWithSplash(primaryStage, splashStage);
                        } else {
                            CodeVerificationController codeVerificationController = new CodeVerificationController();
                            codeVerificationController.start(primaryStage);
                            splashStage.close();
                        }
                    } catch (Exception e) {
                        AppLogger.log("[Main] Failed to start application: " + e.getMessage());
                        e.printStackTrace();
                        splashStage.close();
                    }
                });
            } catch (Exception e) {
                AppLogger.log("[Main] Background init failed: " + e.getMessage());
                e.printStackTrace();
                javafx.application.Platform.runLater(splashStage::close);
            }
        }, "Splash-Init-Thread").start();
    }

    private Stage createSplashStage() {
        Stage splashStage = new Stage();
        splashStage.initStyle(StageStyle.TRANSPARENT);

        VBox root = new VBox(20);
        root.setAlignment(Pos.CENTER);
        // Dark gradient matching the app's theme
        root.setStyle("-fx-background-color: linear-gradient(to bottom right, #1e1e1e, #0a0a0a); " +
                      "-fx-border-color: #333333; -fx-border-width: 1px; -fx-background-radius: 10px; -fx-border-radius: 10px;");
        root.setPrefSize(400, 300);

        try {
            java.net.URL logoUrl = getClass().getResource("/images/logo.png");
            if (logoUrl != null) {
                ImageView logoView = new ImageView(new Image(logoUrl.toExternalForm()));
                logoView.setFitWidth(150);
                logoView.setPreserveRatio(true);
                root.getChildren().add(logoView);
            } else {
                AppLogger.log("[Main] logo.png not found in resources");
            }
        } catch (Exception e) {
            AppLogger.log("[Main] Could not load logo for splash screen: " + e.getMessage());
        }

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setStyle("-fx-progress-color: #1DB954;"); // Spotify-like green or app theme color
        spinner.setMaxSize(40, 40);
        spinner.setScaleX(-1);

        Label messageLabel = new Label();
        messageLabel.setTextFill(Color.WHITE);
        messageLabel.setStyle("-fx-font-family: 'Arial'; -fx-font-size: 14px;");
        
        try {
            String savedLang = SessionManager.getLanguage();
            Locale loc = new Locale(savedLang != null ? savedLang : "es");
            ResourceBundle bundle = ResourceBundle.getBundle("i18n.messages", loc);
            messageLabel.setText(bundle.getString("splash.loading"));
        } catch (Exception e) {
            messageLabel.setText("La aplicación se está iniciando. Por favor, espere");
        }

        root.getChildren().addAll(spinner, messageLabel);

        Scene scene = new Scene(root, 400, 300);
        scene.setFill(Color.TRANSPARENT);
        splashStage.setScene(scene);
        splashStage.centerOnScreen();

        return splashStage;
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