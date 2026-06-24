package com.musicplayer.scamusica.service;

import com.musicplayer.scamusica.util.AppLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MemoryWatchdog {

    private static MemoryWatchdog instance;
    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService cacheClearScheduler;
    private volatile boolean running = false;

    // Cleanup threshold — triggers GC + callbacks (2.0 GB)
    private static final int THRESHOLD_MB = 2000;

    // Restart threshold — if RAM is above this at midnight, restart (3.5 GB)
    private static final int RESTART_THRESHOLD_MB = 3500;

    // ✅ Flag to prevent multiple restarts in same session
    private volatile boolean restartTriggered = false;

    // Cleanup callbacks (e.g. for ImageCache, PlayerController image views)
    private final List<Runnable> cleanupCallbacks = new ArrayList<>();

    // Pre-restart callbacks (e.g. for saving state before restart)
    private final List<Runnable> preRestartCallbacks = new ArrayList<>();

    private MemoryWatchdog() {
    }

    public static MemoryWatchdog getInstance() {
        if (instance == null) {
            instance = new MemoryWatchdog();
        }
        return instance;
    }

    public void registerCleanupCallback(Runnable callback) {
        if (callback != null) {
            cleanupCallbacks.add(callback);
        }
    }

    public void registerPreRestartCallback(Runnable callback) {
        if (callback != null) {
            preRestartCallbacks.add(callback);
        }
    }

    public void runPreRestartCallbacks() {
        for (Runnable callback : preRestartCallbacks) {
            try {
                callback.run();
            } catch (Exception e) {
                AppLogger.log("[MemoryWatchdog] Error in pre-restart callback: " + e.getMessage());
            }
        }
    }

    public void start() {
        if (running)
            return;
        running = true;

        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "MemoryWatchdog-Thread");
            t.setDaemon(true);
            return t;
        });

        // Run memory check + cleanup every 1 hour
        scheduler.scheduleAtFixedRate(this::checkMemoryAndClean, 60, 60, TimeUnit.MINUTES);
        AppLogger.log("[MemoryWatchdog] Started. Memory check every 1 hour. "
                + "Cleanup threshold: " + THRESHOLD_MB + " MB. "
                + "Restart threshold: " + RESTART_THRESHOLD_MB + " MB (at midnight).");

        // ✅ Schedule daily midnight restart check
        long delayToMidnight = computeDelayToNextMidnight();
        AppLogger.log("[MemoryWatchdog] Next midnight restart check in " + (delayToMidnight / 60000) + " minutes.");
        scheduler.scheduleAtFixedRate(
                this::checkMidnightRestart,
                delayToMidnight,
                TimeUnit.DAYS.toMillis(1),
                TimeUnit.MILLISECONDS);

        cacheClearScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CacheClear-Thread");
            t.setDaemon(true);
            return t;
        });

        // Clear OS page cache every 1 hour
        cacheClearScheduler.scheduleAtFixedRate(this::clearOsPageCache, 60, 60, TimeUnit.MINUTES);
    }

    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (cacheClearScheduler != null) {
            cacheClearScheduler.shutdownNow();
            cacheClearScheduler = null;
        }
        AppLogger.log("[MemoryWatchdog] Stopped.");
    }

    // ─────────────────────────────────────────────
    // ✅ MIDNIGHT RESTART CHECK — called once per day at midnight
    // ─────────────────────────────────────────────
    private void checkMidnightRestart() {
        try {
            AppLogger.log("[MemoryWatchdog] 🕛 Midnight restart check triggered.");
            long usedMB = getOsUsedMemoryMB();

            if (usedMB == -1) {
                // Fallback to JVM if OS check fails
                Runtime rt = Runtime.getRuntime();
                usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                AppLogger.log("[MemoryWatchdog] OS check failed at midnight. JVM heap used: " + usedMB + " MB");
            } else {
                AppLogger.log("[MemoryWatchdog] Midnight RAM check: " + usedMB
                        + " MB used (OS-level). Restart threshold: " + RESTART_THRESHOLD_MB + " MB.");
            }

            if (usedMB > RESTART_THRESHOLD_MB) {
                AppLogger.log("[MemoryWatchdog] ⚠️ RAM exceeds restart threshold at midnight (" + usedMB + " MB > "
                        + RESTART_THRESHOLD_MB + " MB). Triggering restart...");
                triggerSelfRestart();
            } else {
                AppLogger.log("[MemoryWatchdog] ✅ Midnight check OK: " + usedMB + " MB. No restart needed.");
                // Reset flag so restart can happen next midnight if needed
                restartTriggered = false;
            }
        } catch (Exception e) {
            AppLogger.log("[MemoryWatchdog] Error in midnight restart check: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // ✅ Compute milliseconds until next midnight
    // ─────────────────────────────────────────────
    private long computeDelayToNextMidnight() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay();
        return ChronoUnit.MILLIS.between(now, nextMidnight);
    }

    // ─────────────────────────────────────────────
    // Memory check + cleanup (every 15 mins)
    // ─────────────────────────────────────────────
    private void checkMemoryAndClean() {
        try {
            long usedMB = getOsUsedMemoryMB();

            if (usedMB == -1) {
                Runtime rt = Runtime.getRuntime();
                usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                AppLogger.log("[MemoryWatchdog] OS check failed. Fallback to JVM heap used: " + usedMB + " MB");
            }

            // Step 1: Force GC and finalization of native objects (VLC/JNA)
            System.gc();
            System.runFinalization();

            // Step 2: Clean temp files (play_*.mp3)
            cleanTempFiles();

            // Step 3: Call registered cleanup callbacks (ImageCache, etc.)
            for (Runnable callback : cleanupCallbacks) {
                try {
                    callback.run();
                } catch (Exception e) {
                    AppLogger.log("[MemoryWatchdog] Error in cleanup callback: " + e.getMessage());
                }
            }

            // Step 4: Force GC again after cleanup
            System.gc();
            System.runFinalization();

            if (usedMB > THRESHOLD_MB) {
                AppLogger.log(
                        "[MemoryWatchdog] ⚠️ THRESHOLD EXCEEDED: " + usedMB + " MB used (OS-level). Cleanup executed.");

                try {
                    Thread.sleep(1000);
                } catch (Exception ignored) {
                }

                long afterMB = getOsUsedMemoryMB();
                AppLogger.log("[MemoryWatchdog] ✅ After cleanup: " + usedMB + " MB → " + afterMB + " MB (OS-level).");
            } else {
                AppLogger.log("[MemoryWatchdog] ✅ OK: " + usedMB + " MB used (Threshold: " + THRESHOLD_MB
                        + " MB). JVM Cleanup executed.");
            }

        } catch (Exception e) {
            AppLogger.log("[MemoryWatchdog] Error during memory check: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // ✅ Trigger self-restart via shell script
    // ─────────────────────────────────────────────
    private void triggerSelfRestart() {
        if (restartTriggered) {
            AppLogger.log("[MemoryWatchdog] Restart already triggered, skipping duplicate call.");
            return;
        }
        restartTriggered = true;

        AppLogger.log("[MemoryWatchdog] 🔄 Triggering auto-restart...");

        // Call pre-restart callbacks (save state, close DB connections, etc.)
        for (Runnable callback : preRestartCallbacks) {
            try {
                callback.run();
            } catch (Exception e) {
                AppLogger.log("[MemoryWatchdog] Error in pre-restart callback: " + e.getMessage());
            }
        }

        try {
            String[] possiblePaths = {
                    "/opt/scamusica/lib/app/restart_scamusica.sh",     // ← #1 (jpackage default)
                    "/opt/scamusica/bin/restart_scamusica.sh",
                    "/opt/scamusica/lib/app/scamusica_wrapper.sh",     // wrapper bhi try karo
                    System.getProperty("user.home") + "/.scamusica/restart_scamusica.sh"
            };

            AppLogger.log("[MemoryWatchdog] 🔍 Searching restart script...");
            File scriptFile = null;
            for (String path : possiblePaths) {
                File f = new File(path);
                boolean exists = f.exists();
                boolean exec = exists && f.canExecute();
                AppLogger.log("   → " + path + " | exists=" + exists + " | executable=" + exec);
                if (exists && exec) {
                    scriptFile = f;
                    break;
                }
            }

            if (scriptFile != null) {
                AppLogger.log("[MemoryWatchdog] ✅ Launching restart script (detached): " + scriptFile.getAbsolutePath());
                // ✅ KEY FIX: Launch fully detached with nohup + & + IO redirect
                ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                        "nohup " + scriptFile.getAbsolutePath() + " > /dev/null 2>&1 &");
                pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File("/dev/null")));
                pb.redirectErrorStream(true);
                pb.start();
            } else {
                AppLogger.log("[MemoryWatchdog] ⚠️ Restart script not found or not executable. Relying on systemd Restart=always if configured.");
            }
        } catch (Exception e) {
            AppLogger.log("[MemoryWatchdog] Failed to launch restart script: " + e.getMessage());
        }

        // Exit JVM after 3s to allow script to start
        new Thread(() -> {
            try {
                Thread.sleep(3000);
            } catch (Exception ignored) {
            }
            AppLogger.log("[MemoryWatchdog] Exiting JVM now for restart.");
            AppLogger.close();
            System.exit(0);
        }, "Watchdog-Restart-Thread").start();
    }

    // ─────────────────────────────────────────────
    // OS RAM check via `free -m`
    // ─────────────────────────────────────────────
    private long getOsUsedMemoryMB() {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("free", "-m");
            p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("Mem:")) {
                        // Format: Mem: total used free shared buff/cache available
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 3) {
                            return Long.parseLong(parts[2]);
                        }
                    }
                }
            }
            try (BufferedReader errReader = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                while (errReader.readLine() != null) {
                }
            }
            p.waitFor();
        } catch (Exception e) {
            AppLogger.log("[MemoryWatchdog] Failed to get OS memory: " + e.getMessage());
        } finally {
            if (p != null)
                p.destroy();
        }
        return -1;
    }

    // ─────────────────────────────────────────────
    // Clean temp play_*.mp3 files
    // ─────────────────────────────────────────────
    private void cleanTempFiles() {
        try {
            File tempDir = new File(System.getProperty("user.home")
                    + File.separator + ".scamusica"
                    + File.separator + "temp");

            if (tempDir.exists() && tempDir.isDirectory()) {
                File[] files = tempDir.listFiles();
                int deletedCount = 0;
                long freedBytes = 0;
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().startsWith("play_") && f.getName().endsWith(".mp3")) {
                            freedBytes += f.length();
                            if (f.delete())
                                deletedCount++;
                        }
                    }
                }
                if (deletedCount > 0) {
                    AppLogger.log("[MemoryWatchdog] Cleaned " + deletedCount
                            + " temp files, freed ~" + (freedBytes / (1024 * 1024)) + " MB");
                }
            }
        } catch (Exception e) {
            AppLogger.log("[MemoryWatchdog] Failed to clean temp files: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // Clear OS page cache (every 1 hour)
    // ─────────────────────────────────────────────
    private void clearOsPageCache() {
        Process syncProc = null;
        Process dropProc = null;
        try {
            AppLogger.log("[MemoryWatchdog] Running scheduled OS page cache clear...");

            syncProc = new ProcessBuilder("sync").start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(syncProc.getInputStream()));
                    BufferedReader e = new BufferedReader(new InputStreamReader(syncProc.getErrorStream()))) {
                while (r.readLine() != null) {
                }
                while (e.readLine() != null) {
                }
            }
            syncProc.waitFor();

            ProcessBuilder pb = new ProcessBuilder("sudo", "sh", "-c", "echo 3 > /proc/sys/vm/drop_caches");
            dropProc = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(dropProc.getInputStream()));
                    BufferedReader e = new BufferedReader(new InputStreamReader(dropProc.getErrorStream()))) {
                while (r.readLine() != null) {
                }
                while (e.readLine() != null) {
                }
            }

            int exitCode = dropProc.waitFor();
            if (exitCode == 0) {
                AppLogger.log("[MemoryWatchdog] ✅ OS buff/cache cleared successfully.");
            } else {
                AppLogger.log("[MemoryWatchdog] ⚠️ Could not clear OS buff/cache. Exit code: " + exitCode
                        + " (sudo required?)");
            }
        } catch (Exception e) {
            AppLogger.log("[MemoryWatchdog] Failed to clear OS buff/cache: " + e.getMessage());
        } finally {
            if (syncProc != null)
                syncProc.destroy();
            if (dropProc != null)
                dropProc.destroy();
        }
    }
}