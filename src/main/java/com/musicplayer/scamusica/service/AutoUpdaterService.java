package com.musicplayer.scamusica.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.musicplayer.scamusica.manager.SessionManager;
import com.musicplayer.scamusica.util.ApiClient;
import com.musicplayer.scamusica.util.AppConfig;
import com.musicplayer.scamusica.util.AppLogger;
import com.musicplayer.scamusica.util.Utility;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AutoUpdaterService {

    private ScheduledExecutorService scheduler;
    private static AutoUpdaterService instance;
    private final String UPDATE_URL = Utility.BASE_URL.get() + Utility.CHECK_UPDATE_ENDPOINT.get();
    private final String UPDATE_SCRIPT_PATH = "/tmp/apply_scamusica_update.sh";
    private final String UPDATE_JAR_DEST = "/opt/scamusica/lib/app/Scamusica-update.jar";

    private AutoUpdaterService() {}

    public static AutoUpdaterService getInstance() {
        if (instance == null) {
            instance = new AutoUpdaterService();
        }
        return instance;
    }

    public void start() {
        if (scheduler != null && !scheduler.isShutdown()) {
            return;
        }

        AppLogger.log("[AutoUpdater] Starting background update checker...");
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AutoUpdater-Thread");
            t.setDaemon(true);
            return t;
        });

        // Check for updates initially after 10 seconds, then every 24 hours
        scheduler.scheduleAtFixedRate(() -> {
            try {
                AppLogger.log("[AutoUpdater] Timer triggered. Checking network status...");
                if (NetworkMonitor.getInstance().isOnline()) {
                    checkForUpdates();
                } else {
                    AppLogger.log("[AutoUpdater] Skipped update check because NetworkMonitor says offline.");
                }
            } catch (Exception e) {
                AppLogger.log("[AutoUpdater] Error during update check: " + e.getMessage());
            }
        }, 10, 24 * 60 * 60, TimeUnit.SECONDS);
    }

    private void checkForUpdates() {
        try {
            AppLogger.log("[AutoUpdater] Checking for updates...");
            String token = SessionManager.loadToken();

            Map<String, String> headers = new HashMap<>();
            if (token != null && !token.isEmpty()) {
                headers.put("Authorization", "Bearer " + token);
            }
            headers.put("Accept", "application/json");

            AppLogger.log("[AutoUpdater] Calling Update API: " + UPDATE_URL);
            String response = ApiClient.get(UPDATE_URL, headers);
            AppLogger.log("[AutoUpdater] API Response: " + response);

            if (response == null || response.isEmpty()) {
                AppLogger.log("[AutoUpdater] API returned empty or null response. Aborting.");
                return;
            }

            JsonObject root = JsonParser.parseString(response).getAsJsonObject();
            if (root.has("version") && root.has("download_url")) {
                String latestVersion = root.get("version").getAsString();
                String downloadUrl = root.get("download_url").getAsString();

                if (isNewerVersion(AppConfig.APP_VERSION, latestVersion)) {
                    AppLogger.log("[AutoUpdater] New version found: " + latestVersion + ". Starting download...");
                    downloadAndApplyUpdate(downloadUrl);
                } else {
                    AppLogger.log("[AutoUpdater] App is up to date (" + AppConfig.APP_VERSION + ")");
                }
            }
        } catch (Exception e) {
            AppLogger.log("[AutoUpdater] Failed to check for updates: " + e.getMessage());
        }
    }

    private boolean isNewerVersion(String current, String latest) {
        String[] currentParts = current.split("\\.");
        String[] latestParts = latest.split("\\.");
        int length = Math.max(currentParts.length, latestParts.length);
        
        for (int i = 0; i < length; i++) {
            int c = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
            int l = i < latestParts.length ? Integer.parseInt(latestParts[i]) : 0;
            if (l > c) return true;
            if (c > l) return false;
        }
        return false;
    }

    private void downloadAndApplyUpdate(String downloadUrlStr) {
        try {
            URL url = new URL(downloadUrlStr);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);

            File destFile = new File(UPDATE_JAR_DEST);
            if (destFile.exists()) {
                destFile.delete();
            }

            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(destFile)) {
                 
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            AppLogger.log("[AutoUpdater] Download complete. Executing update script...");

            // Dynamically write the shell script to disk so the user doesn't need to include it in the installer
            String scriptContent = "#!/bin/bash\n"
                    + "sleep 5\n"
                    + "APP_DIR=\"/opt/scamusica/lib/app\"\n"
                    + "UPDATE_JAR=\"$APP_DIR/Scamusica-update.jar\"\n"
                    + "if [ -f \"$UPDATE_JAR\" ]; then\n"
                    + "    rm -f $APP_DIR/Scamusica-*.jar\n"
                    + "    mv \"$UPDATE_JAR\" \"$APP_DIR/Scamusica-latest.jar\"\n"
                    + "    chmod 755 \"$APP_DIR/Scamusica-latest.jar\"\n"
                    + "    sudo systemctl restart scamusica\n"
                    + "fi\n";

            File scriptFile = new File(UPDATE_SCRIPT_PATH);
            try (FileOutputStream fos = new FileOutputStream(scriptFile)) {
                fos.write(scriptContent.getBytes());
            }
            scriptFile.setExecutable(true, false);

            if (scriptFile.exists()) {
                Runtime.getRuntime().exec(new String[]{"bash", UPDATE_SCRIPT_PATH});
                
                // Allow the script a moment to start before shutting down the JVM
                Thread.sleep(1000);
                
                AppLogger.log("[AutoUpdater] Shutting down application for update.");
                System.exit(0);
            } else {
                AppLogger.log("[AutoUpdater] ERROR: Failed to create update script at " + UPDATE_SCRIPT_PATH);
            }

        } catch (Exception e) {
            AppLogger.log("[AutoUpdater] Update application failed: " + e.getMessage());
        }
    }
}
