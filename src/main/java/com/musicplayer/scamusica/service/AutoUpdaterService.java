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
            String logPath = "/home/pi/scamusica-updater.log";

            // The scamusica service is a USER service (systemctl --user), NOT a system service.
            // So we must use "systemctl --user" commands, running as the pi user.
            String scriptContent = "#!/bin/bash\n"
                    + "exec > " + logPath + " 2>&1\n"
                    + "echo \"[$(date)] OTA Update script started\"\n"
                    + "echo \"[$(date)] Waiting for Java process to exit...\"\n"
                    + "sleep 5\n"
                    + "\n"
                    + "# Stop the user service to prevent Restart=always from fighting us\n"
                    + "echo \"[$(date)] Stopping scamusica user service...\"\n"
                    + "export XDG_RUNTIME_DIR=/run/user/$(id -u pi)\n"
                    + "export DBUS_SESSION_BUS_ADDRESS=unix:path=$XDG_RUNTIME_DIR/bus\n"
                    + "systemctl --user stop scamusica.service 2>/dev/null || true\n"
                    + "sleep 2\n"
                    + "\n"
                    + "# Kill any remaining Java processes just in case\n"
                    + "pkill -f 'com.musicplayer.scamusica.Main' 2>/dev/null || true\n"
                    + "sleep 1\n"
                    + "\n"
                    + "# Install the new DEB package\n"
                    + "echo \"[$(date)] Installing DEB: " + debFilePath + "\"\n"
                    + "sudo dpkg -i " + debFilePath + "\n"
                    + "DPKG_EXIT=$?\n"
                    + "echo \"[$(date)] dpkg exit code: $DPKG_EXIT\"\n"
                    + "\n"
                    + "# Reload systemd and restart the service\n"
                    + "echo \"[$(date)] Reloading systemd daemon...\"\n"
                    + "systemctl --user daemon-reload\n"
                    + "echo \"[$(date)] Starting scamusica service...\"\n"
                    + "systemctl --user start scamusica.service\n"
                    + "START_EXIT=$?\n"
                    + "echo \"[$(date)] systemctl start exit code: $START_EXIT\"\n"
                    + "\n"
                    + "# Cleanup\n"
                    + "rm -f " + debFilePath + "\n"
                    + "echo \"[$(date)] OTA Update complete!\"\n";

            File scriptFile = new File(scriptPath);
            try (FileOutputStream fos = new FileOutputStream(scriptFile)) {
                fos.write(scriptContent.getBytes());
            }
            scriptFile.setExecutable(true, true);

            AppLogger.log("[AutoUpdater] Update script written to: " + scriptPath);

            // Show a popup to the user in the UI thread
            javafx.application.Platform.runLater(() -> {
                try {
                    javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
                    alert.setTitle("Update Available");
                    alert.setHeaderText("New Version Downloaded");
                    alert.setContentText("A new version of the player has been successfully downloaded.\nThe player will now automatically restart to apply the update.");
                    alert.show();
                } catch (Exception e) {}
            });

            // Wait 5 seconds so the user can read the popup
            Thread.sleep(5000);

            // Launch the script using systemd-run --user to create a transient unit.
            // This fully escapes the scamusica.service cgroup so the script isn't killed
            // when it stops the scamusica.service!
            AppLogger.log("[AutoUpdater] Launching update script via systemd-run...");
            Process p = Runtime.getRuntime().exec(new String[]{
                "systemd-run", "--user", "--quiet", "bash", scriptPath
            });

            p.waitFor();

            AppLogger.log("[AutoUpdater] Shutting down application for DEB package upgrade.");
            System.exit(0);

        } catch (Exception e) {
            AppLogger.log("[AutoUpdater] Update application failed: " + e.getMessage());
        }
    }
}
