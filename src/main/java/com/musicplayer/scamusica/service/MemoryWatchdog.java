package com.musicplayer.scamusica.service;

import com.musicplayer.scamusica.util.AppLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MemoryWatchdog {

    private static MemoryWatchdog instance;
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    // Threshold in MB based on OS level free -m output
    // User requested around 1.1GB (1100 MB)
    private static final int THRESHOLD_MB = 1100;

    // Cleanup callbacks (e.g. for ImageCache, PlayerController image views)
    private final List<Runnable> cleanupCallbacks = new ArrayList<>();

    private MemoryWatchdog() {}

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

    public void start() {
        if (running) return;
        running = true;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MemoryWatchdog-Thread");
            t.setDaemon(true);
            return t;
        });

        // Run every 15 minutes as requested
        scheduler.scheduleAtFixedRate(this::checkMemoryAndClean, 15, 15, TimeUnit.MINUTES);
        AppLogger.log("[MemoryWatchdog] Started. Monitoring OS memory usage every 15 mins. Threshold: " + THRESHOLD_MB + " MB");
    }

    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        AppLogger.log("[MemoryWatchdog] Stopped");
    }

    private void checkMemoryAndClean() {
        try {
            long usedMB = getOsUsedMemoryMB();

            if (usedMB == -1) {
                // OS check failed, fallback to JVM check just in case
                Runtime rt = Runtime.getRuntime();
                usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                AppLogger.log("[MemoryWatchdog] OS check failed. Fallback to JVM heap used: " + usedMB + " MB");
            }

            if (usedMB > THRESHOLD_MB) {
                AppLogger.log("[MemoryWatchdog] ⚠️ THRESHOLD EXCEEDED: " + usedMB + " MB used (OS-level)");

                // Step 1: Force GC
                System.gc();

                // Step 2: Clean temp files (play_*.mp3)
                cleanTempFiles();

                // Step 3: Call registered cleanup callbacks (ImageCache, etc.)
                for (Runnable callback : cleanupCallbacks) {
                    try {
                        callback.run();
                    } catch (Exception e) {
                        AppLogger.log("[MemoryWatchdog] Error in callback: " + e.getMessage());
                    }
                }

                // Step 4: Force GC again after cleanup
                System.gc();

                // Step 5: Log result
                long afterMB = getOsUsedMemoryMB();
                AppLogger.log("[MemoryWatchdog] ✅ CLEANUP DONE: " + usedMB + " MB -> " + afterMB + " MB (OS-level)");
            } else {
                AppLogger.log("[MemoryWatchdog] ✅ OK: " + usedMB + " MB used (Threshold: " + THRESHOLD_MB + " MB)");
            }

        } catch (Exception e) {
            AppLogger.log("[MemoryWatchdog] Error during check: " + e.getMessage());
        }
    }

    private long getOsUsedMemoryMB() {
        try {
            // Using free -m command for linux/raspberry pi
            ProcessBuilder pb = new ProcessBuilder("free", "-m");
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Mem:")) {
                    // Line format: Mem: total used free shared buff/cache available
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 3) {
                        return Long.parseLong(parts[2]);
                    }
                }
            }
            p.waitFor();
        } catch (Exception e) {
            AppLogger.log("[MemoryWatchdog] Failed to get OS memory: " + e.getMessage());
        }
        return -1;
    }

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
                            if (f.delete()) {
                                deletedCount++;
                            }
                        }
                    }
                }
                if (deletedCount > 0) {
                    AppLogger.log("[MemoryWatchdog] Cleaned " + deletedCount + " temp files, freed ~" + (freedBytes / (1024 * 1024)) + " MB");
                }
            }
        } catch (Exception e) {
            AppLogger.log("[MemoryWatchdog] Failed to clean temp files: " + e.getMessage());
        }
    }
}
