package com.musicplayer.scamusica;

import com.musicplayer.scamusica.controller.CodeVerificationController;
import com.musicplayer.scamusica.controller.PlayerController;
import com.musicplayer.scamusica.manager.LanguageManager;
import com.musicplayer.scamusica.manager.SessionManager;
import com.musicplayer.scamusica.util.AppLogger;
import javafx.application.Application;
import javafx.stage.Stage;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;

public class Main extends Application {

    // ✅ Flag to prevent multiple restart attempts
    private static volatile boolean restartInitiated = false;

    private static void setupVlc() {
        try {
            String basePath = new java.io.File(
                    Main.class.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .getParent();

            String vlcPath = basePath + "/lib/vlc";
            String pluginPath = vlcPath + "/plugins";

            System.setProperty("jna.library.path", vlcPath);
            System.setProperty("VLC_PLUGIN_PATH", pluginPath);

            System.out.println("✅ VLC Path: " + vlcPath);
            System.out.println("✅ Plugin Path: " + pluginPath);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * ✅ Checks if a throwable (or any of its causes) is JNA-related.
     */
    private static boolean isJnaRelated(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            String msg = cause.getMessage();
            String str = cause.toString();
            if ((msg != null && (msg.contains("JNA") || msg.contains("com.sun.jna") || msg.contains("Native")))
                    || (str != null && (str.contains("JNA") || str.contains("com.sun.jna")))) {
                return true;
            }
            // Also check stack trace elements for JNA package
            for (StackTraceElement ste : cause.getStackTrace()) {
                if (ste.getClassName().startsWith("com.sun.jna")
                        || ste.getClassName().startsWith("uk.co.caprica.vlcj")) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * ✅ Converts full stack trace to a String for logging.
     */
    private static String getFullStackTrace(Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sw.toString();
    }

    /**
     * ✅ Core restart logic — shared by both uncaught handler and JavaFX thread
     * handler.
     * Prevents double-triggering with restartInitiated flag.
     */
    static void handleJnaErrorAndRestart(String source, Throwable throwable) {
        if (restartInitiated)
            return;
        restartInitiated = true;

        // ✅ Log full exception with stack trace
        AppLogger.log("[" + source + "] ⚠️ JNA/VLC Error detected!");
        AppLogger.log("[" + source + "] Exception: " + throwable.toString());
        AppLogger.log("[" + source + "] Full Stack Trace:\n" + getFullStackTrace(throwable));

        // Log each cause in chain
        Throwable cause = throwable.getCause();
        int depth = 1;
        while (cause != null) {
            AppLogger.log("[" + source + "] Caused by (depth=" + depth + "): " + cause.toString()
                    + "\n" + getFullStackTrace(cause));
            cause = cause.getCause();
            depth++;
        }

        AppLogger.log("[" + source + "] Initiating application restart due to JNA error...");

        // ✅ Find and launch restart script
        try {
            String[] possiblePaths = {
                    System.getProperty("user.home") + File.separator + "scamusica" + File.separator
                            + "restart_scamusica.sh",
                    System.getProperty("user.dir") + File.separator + "scripts" + File.separator
                            + "restart_scamusica.sh",
                    System.getProperty("user.dir") + File.separator + "restart_scamusica.sh",
                    "/opt/scamusica/bin/restart_scamusica.sh",
                    "/opt/scamusica/lib/app/restart_scamusica.sh"
            };

            File scriptFile = null;
            for (String path : possiblePaths) {
                File f = new File(path);
                if (f.exists() && f.canExecute()) {
                    scriptFile = f;
                    break;
                }
            }

            if (scriptFile != null) {
                AppLogger.log("[" + source + "] Launching restart script: " + scriptFile.getAbsolutePath());
                new ProcessBuilder(scriptFile.getAbsolutePath()).start();
            } else {
                AppLogger.log("[" + source + "] ⚠️ Restart script not found in any known path. Checked: "
                        + String.join(", ", possiblePaths));
                AppLogger.log("[" + source + "] Relying on systemd Restart=always if configured.");
            }
        } catch (Exception e) {
            AppLogger.log("[" + source + "] Failed to launch restart script: " + e.getMessage()
                    + "\n" + getFullStackTrace(e));
        }

        // ✅ Exit JVM after delay to allow script to start
        new Thread(() -> {
            try {
                Thread.sleep(3000);
            } catch (Exception ignored) {
            }
            AppLogger.log("[" + source + "] Exiting JVM now due to JNA error.");
            AppLogger.close();
            System.exit(1);
        }, "JNA-Restart-Thread").start();
    }

    @Override
    public void start(Stage primaryStage) {
        System.setProperty("java.net.useSystemProxies", "true");

        // ✅ TESTING ONLY — 30 seconds baad JNA error simulate hoga
        // GitHub Actions se build karo, Pi pe deploy karo, 30 sec wait karo
        // Restart hone ke baad yeh line hata do
        new Thread(() -> {
            try { Thread.sleep(30000); } catch (Exception e) {}
            System.err.println("Exception in thread \"media-events\" JNA: error handling callback exception");
            System.err.println("JNA: failed to create structure");
        }, "media-events").start();

        // ✅ Catch exceptions thrown on the JavaFX Application Thread (FX thread errors
        // are NOT caught by Thread.setDefaultUncaughtExceptionHandler)
        Thread.currentThread().setUncaughtExceptionHandler((thread, throwable) -> {
            AppLogger.log("[Main-FXThread] Uncaught exception on JavaFX thread: " + throwable.toString());
            AppLogger.log("[Main-FXThread] Stack Trace:\n" + getFullStackTrace(throwable));

            if (isJnaRelated(throwable)) {
                handleJnaErrorAndRestart("Main-FXThread", throwable);
            }
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

        // ✅ Handler 1: Catches JNA errors thrown on background/non-FX threads
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            // Always log every uncaught exception with full stack trace
            AppLogger.log("[Main] ⚠️ Uncaught Exception on thread [" + thread.getName() + "]: "
                    + throwable.toString());
            AppLogger.log("[Main] Full Stack Trace:\n" + getFullStackTrace(throwable));

            // Log suppressed exceptions if any
            for (Throwable suppressed : throwable.getSuppressed()) {
                AppLogger.log("[Main] Suppressed: " + suppressed.toString()
                        + "\n" + getFullStackTrace(suppressed));
            }

            if (isJnaRelated(throwable)) {
                handleJnaErrorAndRestart("Main-UncaughtHandler", throwable);
            }
        });

        // ✅ Handler 2: Some JNA/VLC errors surface as java.lang.Error (not Exception)
        // Runtime.halt or UnsatisfiedLinkError etc. — caught above since Throwable is
        // used.
        // But we also set a shutdown hook to log if JVM exits unexpectedly.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            AppLogger.log("[Main] ⚠️ JVM Shutdown Hook triggered. Application is exiting.");
            // Note: don't call AppLogger.close() here — it may already be closed on normal
            // exit
        }, "ShutdownHook-Logger"));

        setupVlc();
        launch(args);
    }
}