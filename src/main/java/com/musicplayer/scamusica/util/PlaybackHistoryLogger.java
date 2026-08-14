package com.musicplayer.scamusica.util;

import com.musicplayer.scamusica.model.PlaylistTrack;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class PlaybackHistoryLogger {

    private static final String BASE_DIR =
            System.getProperty("user.home")
                    + File.separator
                    + ".scamusica";

    private static final String LOG_FILE =
            BASE_DIR + File.separator + "playback-history.log";

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("dd-MM-yyyy hh:mm:ss a");

    static {
        try {

            File dir = new File(BASE_DIR);

            if (!dir.exists()) {
                dir.mkdirs();
            }

            File file = new File(LOG_FILE);

            if (!file.exists()) {
                file.createNewFile();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static final long MAX_LOG_SIZE = 5 * 1024 * 1024; // 5 MB

    public static synchronized void logSong(PlaylistTrack track) {

        try {
            // Cap the log file size — if over 5MB, delete it to prevent huge memory spikes
            File logFile = new File(LOG_FILE);
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                try {
                    logFile.delete();
                    logFile.createNewFile();
                    AppLogger.log("[HISTORY] Log file exceeded 5MB and was reset to prevent memory spikes.");
                } catch (Exception deleteEx) {
                    AppLogger.log("[HISTORY] Failed to reset log: " + deleteEx.getMessage());
                }
            }
        } catch (Exception ignored) {}

        try (BufferedWriter writer =
                     new BufferedWriter(new FileWriter(LOG_FILE, true))) {

            String time = LocalDateTime.now().format(FORMATTER);

            String log =
                    "\n==================================================\n" +
                            "TIME       : " + time + "\n" +
                            "SONG ID    : " + track.getId() + "\n" +
                            "TITLE      : " + track.getTitle() + "\n" +
                            "PLAYLIST   : " + track.getFolderTitle() + "\n" +
                            "URL        : " + track.getUrl() + "\n" +
                            "==================================================\n";

            writer.write(log);

            AppLogger.log("[HISTORY] Logged -> " + track.getTitle());

            // Sync to server (additive — does not affect local logging)
            try {
                com.musicplayer.scamusica.service.LogSyncService.getInstance()
                        .addSongLog(track.getId(), track.getTitle(),
                                track.getFolderTitle(), track.getUrl());
            } catch (Exception syncEx) {
                AppLogger.log("[HISTORY] Server sync queue failed: " + syncEx.getMessage());
            }

        } catch (Exception e) {

            AppLogger.log("[HISTORY ERROR] " + e.getMessage());

            e.printStackTrace();
        }
    }
}