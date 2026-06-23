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
    private ScheduledExecutorService cacheClearScheduler;
    private volatile boolean running = false;

    // Threshold in MB based on OS level free -m output
    // User requested around 1.1GB (1100 MB)
    private static final int THRESHOLD_MB = 1100;

    // Restart threshold in MB (1200 MB for testing)
    private static final int RESTART_THRESHOLD_MB = 1200;
    private volatile boolean restartTriggered = false;

    // Cleanup callbacks (e.g. for ImageCache, PlayerController image views)
    private final List<Runnable> cleanupCallbacks = new ArrayList<>();
    
    // Pre-restart callbacks (e.g. for saving state)
    private final List<Runnable> preRestartCallbacks = new ArrayList<>();

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

    public void registerPreRestartCallback(Runnable callback) {
        if (callback != null) {
            preRestartCallbacks.add(callback);
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

        // Run JVM/OS check and temp file cleanup every 15 minutes
        scheduler.scheduleAtFixedRate(this::checkMemoryAndClean, 15, 15, TimeUnit.MINUTES);
        AppLogger.log("[MemoryWatchdog] Started. Monitoring OS memory usage every 15 mins. Threshold: " + THRESHOLD_MB + " MB");

        cacheClearScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CacheClear-Thread");
            t.setDaemon(true);
            return t;
        });
        
        // Clear OS page cache every 1 hour (60 mins)
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

            // ALWAYS Step 1: Force GC and finalization of native objects (VLC/JNA)
            System.gc();
            System.runFinalization();

            // ALWAYS Step 2: Clean temp files (play_*.mp3)
            cleanTempFiles();

            // ALWAYS Step 3: Call registered cleanup callbacks (ImageCache, etc.)
            for (Runnable callback : cleanupCallbacks) {
                try {
                    callback.run();
                } catch (Exception e) {
                    AppLogger.log("[MemoryWatchdog] Error in callback: " + e.getMessage());
                }
            }

            // ALWAYS Step 4: Force GC again after cleanup
            System.gc();
            System.runFinalization();

            if (usedMB > THRESHOLD_MB) {
                AppLogger.log("[MemoryWatchdog] ⚠️ THRESHOLD EXCEEDED: " + usedMB + " MB used (OS-level)");

                // Wait for a second to allow OS to reclaim memory
                try { Thread.sleep(1000); } catch (Exception ignored) {}

                // Log result after cleanup
                long afterMB = getOsUsedMemoryMB();
                AppLogger.log("[MemoryWatchdog] ✅ CLEANUP DONE: " + usedMB + " MB -> " + afterMB + " MB (OS-level)");
                
                // Trigger restart if still above restart threshold
                if (afterMB > RESTART_THRESHOLD_MB && !restartTriggered) {
                    AppLogger.log("[MemoryWatchdog] 🚨 RESTART THRESHOLD EXCEEDED after cleanup. Triggering restart...");
                    triggerSelfRestart();
                }
            } else {
                AppLogger.log("[MemoryWatchdog] ✅ OK: " + usedMB + " MB used (Threshold: " + THRESHOLD_MB + " MB). JVM Cleanup executed.");
            }

        } catch (Exception e) {
            AppLogger.log("[MemoryWatchdog] Error during check: " + e.getMessage());
        }
    }

    private long getOsUsedMemoryMB() {
        Process p = null;
        try {
            // Using free -m command for linux/raspberry pi
            ProcessBuilder pb = new ProcessBuilder("free", "-m");
            p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
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
            }
            // Drain error stream just in case
            try (BufferedReader errReader = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                while (errReader.readLine() != null) {}
            }
            p.waitFor();
        } catch (Exception e) {
            AppLogger.log("[MemoryWatchdog] Failed to get OS memory: " + e.getMessage());
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
        return -1;
    }

    private void triggerSelfRestart() {
        if (restartTriggered) return;
        restartTriggered = true;
        
        AppLogger.log("[MemoryWatchdog] Triggering auto-restart...");
        
        // Call pre-restart callbacks to save state
        for (Runnable callback : preRestartCallbacks) {
            try {
                callback.run();
            } catch (Exception e) {
                AppLogger.log("[MemoryWatchdog] Error in pre-restart callback: " + e.getMessage());
            }
        }
        
        try {
            // Check if restart script exists
            String scriptPath = System.getProperty("user.home") + File.separator + "scamusica" + File.separator + "restart_scamusica.sh";
            File scriptFile = new File(scriptPath);
            
            if (scriptFile.exists() && scriptFile.canExecute()) {
                AppLogger.log("[MemoryWatchdog] Launching restart script: " + scriptPath);
                new ProcessBuilder(scriptPath).start();
            } else {
                AppLogger.log("[MemoryWatchdog] Restart script not found or not executable at: " + scriptPath + ". Relying on systemd Restart=always if configured.");
            }
        } catch (Exception e) {
            AppLogger.log("[MemoryWatchdog] Failed to launch restart script: " + e.getMessage());
        }
        
        // Wait briefly to allow script to start, then exit
        new Thread(() -> {
            try { Thread.sleep(3000); } catch (Exception ignored) {}
            AppLogger.log("[MemoryWatchdog] Exiting JVM now.");
            AppLogger.close();
            System.exit(0);
        }).start();
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

    private void clearOsPageCache() {
        Process syncProc = null;
        Process dropProc = null;
        try {
            AppLogger.log("[MemoryWatchdog] Running scheduled clear OS page cache...");
            // First run 'sync' to write any pending data to SD Card
            syncProc = new ProcessBuilder("sync").start();
            // Drain streams
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(syncProc.getInputStream()));
                 BufferedReader errReader = new BufferedReader(new InputStreamReader(syncProc.getErrorStream()))) {
                while (reader.readLine() != null) {}
                while (errReader.readLine() != null) {}
            }
            syncProc.waitFor();
            
            // Then drop caches (requires sudo without password on Raspberry Pi)
            ProcessBuilder pb = new ProcessBuilder("sudo", "sh", "-c", "echo 3 > /proc/sys/vm/drop_caches");
            dropProc = pb.start();
            
            // Drain streams
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(dropProc.getInputStream()));
                 BufferedReader errReader = new BufferedReader(new InputStreamReader(dropProc.getErrorStream()))) {
                while (reader.readLine() != null) {}
                while (errReader.readLine() != null) {}
            }
            
            int exitCode = dropProc.waitFor();
            
            if (exitCode == 0) {
                AppLogger.log("[MemoryWatchdog] ✅ OS buff/cache cleared successfully via sysctl.");
            } else {
                AppLogger.log("[MemoryWatchdog] ⚠️ Could not clear OS buff/cache. Exit code: " + exitCode + " (sudo required)");
            }
        } catch (Exception e) {
            AppLogger.log("[MemoryWatchdog] Failed to clear OS buff/cache: " + e.getMessage());
        } finally {
            if (syncProc != null) syncProc.destroy();
            if (dropProc != null) dropProc.destroy();
        }
    }
}
