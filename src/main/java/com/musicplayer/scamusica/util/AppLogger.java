package com.musicplayer.scamusica.util;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

public class AppLogger {

    private static PrintWriter writer;
    private static File currentLogFile;
    private static final long MAX_LOG_SIZE = 50 * 1024 * 1024; // 50 MB

    public static void init() {
        try {
            String baseDir = System.getProperty("user.home")
                    + File.separator + ".scamusica"
                    + File.separator + "logs";

            File dir = new File(baseDir);
            if (!dir.exists()) dir.mkdirs();

            // Cleanup old log files — keep only the latest 5
            File[] existingLogs = dir.listFiles((d, name) -> name.startsWith("player_") && name.endsWith(".log"));
            if (existingLogs != null && existingLogs.length > 5) {
                java.util.Arrays.sort(existingLogs, java.util.Comparator.comparingLong(File::lastModified));
                for (int i = 0; i < existingLogs.length - 5; i++) {
                    existingLogs[i].delete();
                }
            }

            String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            currentLogFile = new File(dir, "player_" + timestamp + ".log");

            writer = new PrintWriter(new FileWriter(currentLogFile, true), true);

            log("[LOGGER] Initialized. File: " + currentLogFile.getAbsolutePath());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void log(String message) {
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
        String finalMsg = "[" + time + "] " + message;

        System.out.println(finalMsg);

        if (currentLogFile != null && currentLogFile.length() > MAX_LOG_SIZE) {
            rotateLog();
        }

        if (writer != null) {
            writer.println(finalMsg);
        }
    }

    private static void rotateLog() {
        try {
            if (writer != null) {
                writer.close();
            }
            File dir = currentLogFile.getParentFile();
            File[] logs = dir.listFiles((d, n) -> n.startsWith("player_") && n.endsWith(".log"));
            if (logs != null && logs.length > 5) {
                java.util.Arrays.sort(logs, java.util.Comparator.comparingLong(File::lastModified));
                for (int i = 0; i < logs.length - 5; i++) {
                    logs[i].delete();
                }
            }
            String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            currentLogFile = new File(dir, "player_" + ts + ".log");
            writer = new PrintWriter(new FileWriter(currentLogFile, true), true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void close() {
        if (writer != null) {
            log("[LOGGER] Closing logger");
            writer.close();
        }
    }
}