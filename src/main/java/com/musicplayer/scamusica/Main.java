package com.musicplayer.scamusica;

import com.musicplayer.scamusica.controller.CodeVerificationController;
import com.musicplayer.scamusica.controller.PlayerController;
import com.musicplayer.scamusica.manager.LanguageManager;
import com.musicplayer.scamusica.manager.SessionManager;
import com.musicplayer.scamusica.util.AppLogger;
import javafx.application.Application;
import javafx.stage.Stage;

import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
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
     * ✅ Checks if a message string is JNA/VLC related.
     */
    private static boolean isJnaRelatedMessage(String msg) {
        if (msg == null)
            return false;
        return msg.contains("JNA")
                || msg.contains("com.sun.jna")
                || msg.contains("failed to create structure")
                || msg.contains("error handling callback")
                || msg.contains("uk.co.caprica.vlcj")
                || msg.contains("Native");
    }

    /**
     * ✅ Checks if a Throwable (or any of its causes/stack) is JNA-related.
     */
    private static boolean isJnaRelated(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (isJnaRelatedMessage(cause.getMessage()) || isJnaRelatedMessage(cause.toString())) {
                return true;
            }
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

    /**
     * ✅ THE KEY FIX: Intercept System.err to catch JNA errors that are printed
     * directly to stderr (not thrown as exceptions). This is how JNA reports
     * "error handling callback" and "failed to create structure" errors.
     *
     * Also used for TESTING — you can simulate by printing to System.err.
     */
    private static void installStderrInterceptor() {
        final PrintStream originalStderr = System.err;

        PrintStream interceptor = new PrintStream(new OutputStream() {
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public void write(int b) {
                char c = (char) b;
                buffer.append(c);

                // Process line by line
                if (c == '\n') {
                    String line = buffer.toString().trim();
                    buffer.setLength(0);

                    // Always write to original stderr so it still shows in terminal
                    originalStderr.println(line);

                    // Always log to AppLogger
                    if (!line.isEmpty()) {
                        AppLogger.log("[STDERR] " + line);
                    }

                    // ✅ Check if this is a JNA error → trigger restart
                    if (isJnaRelatedMessage(line) && !restartInitiated) {
                        handleJnaErrorAndRestart("StderrInterceptor", new RuntimeException("Intercepted JNA Error: " + line));
                    }
                }
            }
        }, true);

        System.setErr(interceptor);
        AppLogger.log("[Main] ✅ stderr interceptor installed. JNA errors will now trigger restart.");
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

        // ✅ Handler 1: Intercept stderr — catches JNA "error handling callback" errors
        installStderrInterceptor();

        // ✅ Handler 2: Catches JNA errors thrown on background/non-FX threads
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