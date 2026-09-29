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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
                    downloadAndApplyUpdate(downloadUrl, latestVersion);
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

    private void downloadAndApplyUpdate(String downloadUrlStr, String latestVersion) {
        try {
            URL url = new URL(downloadUrlStr);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);

            // Add Authorization token since this is now an API route
            String token = SessionManager.loadToken();
            if (token != null && !token.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }

            // CRITICAL: Write DEB to /home/pi/ NOT /tmp/
            // The scamusica service uses PrivateTmp (systemd default), so /tmp/ files
            // are private to the service and get DELETED when the service stops.
            String debFileName = "scamusica-" + latestVersion + ".deb";
            String debFilePath = "/home/pi/" + debFileName;
            File destFile = new File(debFilePath);
            if (destFile.exists()) {
                destFile.delete();
            }

            boolean foundDeb = false;

            // Extract the DEB from the ZIP stream
            try (InputStream in = connection.getInputStream()) {
                if (downloadUrlStr.endsWith(".zip") || downloadUrlStr.contains("download-update")) {
                    AppLogger.log("[AutoUpdater] Downloading and extracting ZIP archive...");
                    try (ZipInputStream zis = new ZipInputStream(in)) {
                        ZipEntry entry;
                        while ((entry = zis.getNextEntry()) != null) {
                            if (entry.getName().endsWith(".deb")) {
                                AppLogger.log("[AutoUpdater] Found DEB in zip: " + entry.getName());
                                try (FileOutputStream out = new FileOutputStream(destFile)) {
                                    byte[] buffer = new byte[8192];
                                    int bytesRead;
                                    while ((bytesRead = zis.read(buffer)) != -1) {
                                        out.write(buffer, 0, bytesRead);
                                    }
                                }
                                foundDeb = true;
                                zis.closeEntry();
                                break; // Only need the first deb
                            }
                            zis.closeEntry();
                        }
                    }
                } else {
                    AppLogger.log("[AutoUpdater] Downloading DEB directly...");
                    try (FileOutputStream out = new FileOutputStream(destFile)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }
                    foundDeb = true;
                }
            }

            if (!foundDeb) {
                AppLogger.log("[AutoUpdater] ERROR: No .deb file found in the download stream.");
                return;
            }

            AppLogger.log("[AutoUpdater] DEB extracted to: " + debFilePath + " (size: " + destFile.length() + " bytes)");

            // CRITICAL: Write the update script to /home/pi/ NOT /tmp/ (same PrivateTmp reason)
            String scriptPath = "/home/pi/apply_scamusica_update.sh";
            String logPath = (AppLogger.getCurrentLogFile() != null) 
                    ? AppLogger.getCurrentLogFile().getAbsolutePath() 
                    : "/home/pi/scamusica-updater.log";

            // The scamusica service is a USER service (systemctl --user), NOT a system service.
            // We just install the deb package in the background. We DO NOT kill or restart the app.
            // The user will get the new version next time they manually start the app.
            String scriptContent = "#!/bin/bash\n"
                    + "exec >> " + logPath + " 2>&1\n"
                    + "echo \"[$(date)] OTA Background Install script started\"\n"
                    + "\n"
                    + "# Install the new DEB package\n"
                    + "echo \"[$(date)] Installing DEB: " + debFilePath + "\"\n"
                    + "sudo dpkg -i " + debFilePath + "\n"
                    + "DPKG_EXIT=$?\n"
                    + "echo \"[$(date)] dpkg exit code: $DPKG_EXIT\"\n"
                    + "\n"
                    + "# Cleanup\n"
                    + "rm -f " + debFilePath + "\n"
                    + "echo \"[$(date)] OTA Update background install complete! Will take effect on next restart.\"\n";

            File scriptFile = new File(scriptPath);
            try (FileOutputStream fos = new FileOutputStream(scriptFile)) {
                fos.write(scriptContent.getBytes());
            }
            scriptFile.setExecutable(true, true);

            AppLogger.log("[AutoUpdater] Update script written to: " + scriptPath);

            // Show a toast notification instead of a blocking popup
            showToast("Update downloaded.\nPlease restart the player manually to apply.");

            // Launch the script via nohup directly. No need to escape cgroups or use systemd-run
            // since we are no longer killing the service!
            AppLogger.log("[AutoUpdater] Launching background install script...");
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", 
                "nohup bash " + scriptPath + " > /dev/null 2>&1 &"
            );
            pb.start();

            AppLogger.log("[AutoUpdater] Update installed in background. Awaiting manual restart by user.");
            
            // REMOVED: System.exit(0) - let the user close it manually!

        } catch (Exception e) {
            AppLogger.log("[AutoUpdater] Update application failed: " + e.getMessage());
        }
    }

    private void showToast(String message) {
        javafx.application.Platform.runLater(() -> {
            try {
                javafx.stage.Stage stage = new javafx.stage.Stage();
                stage.initStyle(javafx.stage.StageStyle.TRANSPARENT);
                stage.setAlwaysOnTop(true);
                
                javafx.scene.control.Label label = new javafx.scene.control.Label(message);
                label.setStyle("-fx-background-color: rgba(0, 0, 0, 0.8); -fx-text-fill: white; -fx-padding: 20px; -fx-font-size: 18px; -fx-background-radius: 10px; -fx-font-weight: bold;");
                label.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
                
                javafx.scene.Scene scene = new javafx.scene.Scene(new javafx.scene.layout.StackPane(label));
                scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
                stage.setScene(scene);
                
                // Show the toast
                stage.show();
                
                // Auto close after 7 seconds
                javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(7));
                delay.setOnFinished(event -> stage.close());
                delay.play();
            } catch (Exception e) {
                AppLogger.log("[AutoUpdater] Failed to show toast: " + e.getMessage());
            }
        });
    }
}
