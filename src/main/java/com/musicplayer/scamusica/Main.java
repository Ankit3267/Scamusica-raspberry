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
     * ✅ Checks if a stderr line is a FATAL JNA error that requires restart.
     * IMPORTANT: We check for "JNA:" prefix to avoid false positives from
     * normal e.printStackTrace() calls throughout the codebase which also
     * write to stderr. Only exact JNA runtime errors should trigger restart.
     */
    private static boolean isFatalJnaStderrError(String line) {
        if (line == null || line.isEmpty()) return false;
        // Only match lines that contain the exact JNA error prefix
        return line.contains("JNA: error handling callback")
            || line.contains("JNA: failed to create structure");
    }

    /**
     * ✅ Checks if an actual Throwable is JNA/VLC related.
     */
    private static boolean isJnaRelatedException(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            String msg = cause.getMessage();
            String str = cause.toString();
            if ((msg != null && (msg.contains("JNA") || msg.contains("com.sun.jna")))
                    || (str != null && (str.contains("JNA") || str.contains("com.sun.jna")))) {
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
     * ✅ Core restart logic — shared by both uncaught handler, JavaFX thread
     * handler, and StderrInterceptor.
     * Prevents double-triggering with restartInitiated flag.
     *
     * Uses a 3-tier restart strategy:
     * 1. Try restart_scamusica.sh script (full cleanup + relaunch)
     * 2. Try direct relaunch of /opt/scamusica/bin/Scamusica (fallback)
     * 3. Rely on systemd Restart=always (last resort)
     *
     * IMPORTANT: All child processes are launched fully detached using
     * "nohup ... &" via bash -c, with IO redirected to /dev/null.
     * This prevents SIGPIPE from killing the restart process when
     * the parent JVM exits via System.exit(1).
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

        boolean relaunchScheduled = false;

        // ✅ Strategy 1: Find and launch restart script (FULLY DETACHED)
        try {
            String[] possibleScriptPaths = {
                    System.getProperty("user.home") + File.separator + "scamusica" + File.separator
                            + "restart_scamusica.sh",
                    System.getProperty("user.dir") + File.separator + "scripts" + File.separator
                            + "restart_scamusica.sh",
                    System.getProperty("user.dir") + File.separator + "restart_scamusica.sh",
                    "/opt/scamusica/bin/restart_scamusica.sh",
                    "/opt/scamusica/lib/app/restart_scamusica.sh"
            };

            for (String path : possibleScriptPaths) {
                File f = new File(path);
                if (f.exists() && f.canExecute()) {
                    AppLogger.log("[" + source + "] ✅ Launching restart script (detached): " + f.getAbsolutePath());
                    // ✅ KEY FIX: Launch fully detached with nohup + & + IO redirect
                    // Without this, script dies from SIGPIPE when parent JVM exits
                    ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                            "nohup " + f.getAbsolutePath() + " > /dev/null 2>&1 &");
                    pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
                    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File("/dev/null")));
                    pb.redirectErrorStream(true);
                    pb.start();
                    relaunchScheduled = true;
                    break;
                }
            }

            if (!relaunchScheduled) {
                AppLogger.log("[" + source + "] ⚠️ Restart script not found. Trying direct relaunch...");
            }
        } catch (Exception e) {
            AppLogger.log("[" + source + "] Failed to launch restart script: " + e.getMessage());
        }

        // ✅ Strategy 2: Direct relaunch of the app binary (FULLY DETACHED)
        if (!relaunchScheduled) {
            try {
                String[] possibleAppPaths = {
                        "/opt/scamusica/bin/Scamusica",
                        "/opt/scamusica/lib/app/scamusica_wrapper.sh"
                };

                for (String path : possibleAppPaths) {
                    File f = new File(path);
                    if (f.exists() && f.canExecute()) {
                        AppLogger.log("[" + source + "] ✅ Direct relaunch (detached) via: " + f.getAbsolutePath());
                        ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                                "sleep 5 && nohup env DISPLAY=:0 " + f.getAbsolutePath() + " > /dev/null 2>&1 &");
                        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
                        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File("/dev/null")));
                        pb.redirectErrorStream(true);
                        pb.start();
                        relaunchScheduled = true;
                        break;
                    }
                }

                if (!relaunchScheduled) {
                    AppLogger.log("[" + source + "] ⚠️ App binary not found. Relying on systemd Restart=always.");
                }
            } catch (Exception e) {
                AppLogger.log("[" + source + "] Failed direct relaunch: " + e.getMessage());
            }
        }

        final boolean didScheduleRelaunch = relaunchScheduled;

        // ✅ Exit JVM after delay to allow script/process to start
        new Thread(() -> {
            try {
                Thread.sleep(didScheduleRelaunch ? 3000 : 1000);
            } catch (Exception ignored) {
            }
            AppLogger.log("[" + source + "] Exiting JVM now. Relaunch scheduled: " + didScheduleRelaunch);
            AppLogger.close();
            System.exit(1);
        }, "JNA-Restart-Thread").start();
    }

    /**
     * ✅ THE KEY FIX: Intercept System.err to catch JNA errors that are printed
     * directly to stderr (not thrown as exceptions). This is how JNA reports
     * "error handling callback" and "failed to create structure" errors.
     *
     * IMPORTANT: Uses ThreadLocal buffer because multiple threads (JavaFX,
     * VLC media-events, MemoryWatchdog, etc.) all write to stderr concurrently.
     * A shared StringBuilder would get corrupted and cause false matches.
     */
    private static void installStderrInterceptor() {
        final PrintStream originalStderr = System.err;

        PrintStream interceptor = new PrintStream(new OutputStream() {
            // ✅ Thread-safe: each thread gets its own buffer
            private final ThreadLocal<StringBuilder> threadBuffer =
                ThreadLocal.withInitial(StringBuilder::new);

            @Override
            public void write(int b) {
                char c = (char) b;
                StringBuilder buffer = threadBuffer.get();
                buffer.append(c);

                // Process line by line
                if (c == '\n') {
                    String line = buffer.toString().trim();
                    buffer.setLength(0);

                    // Always write to original stderr so it still shows in terminal
                    originalStderr.println(line);

                    // ✅ Only check for fatal JNA errors → trigger restart
                    // Do NOT log every stderr line to AppLogger here, because
                    // AppLogger itself could cause stderr output (infinite loop risk)
                    if (isFatalJnaStderrError(line) && !restartInitiated) {
                        AppLogger.log("[StderrInterceptor] ⚠️ Fatal JNA error detected: " + line);
                        handleJnaErrorAndRestart("StderrInterceptor", new RuntimeException("Intercepted JNA Error: " + line));
                    }
                }
            }
        }, true);

        System.setErr(interceptor);
        AppLogger.log("[Main] ✅ stderr interceptor installed. Fatal JNA errors will trigger restart.");
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

            if (isJnaRelatedException(throwable)) {
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

            if (isJnaRelatedException(throwable)) {
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