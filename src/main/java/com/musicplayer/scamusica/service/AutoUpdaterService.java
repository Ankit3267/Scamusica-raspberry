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

            String debFileName = "scamusica-" + latestVersion + ".deb";
            String debFilePath = "/tmp/" + debFileName;
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

            AppLogger.log("[AutoUpdater] DEB extraction complete. Preparing update script...");

            // Create a shell script to run dpkg -i safely outside of the Java process
            String scriptPath = "/tmp/apply_scamusica_update.sh";
            String scriptContent = "#!/bin/bash\n"
                    + "sleep 5\n"
                    + "sudo dpkg -i " + debFilePath + "\n"
                    + "rm -f " + debFilePath + "\n"
                    + "rm -f /tmp/apply_scamusica_update.sh\n";

            File scriptFile = new File(scriptPath);
            try (FileOutputStream fos = new FileOutputStream(scriptFile)) {
                fos.write(scriptContent.getBytes());
            }
            scriptFile.setExecutable(true, false);

            // Execute the script using systemd-run to escape the current service's cgroup.
            // This prevents systemd from killing the dpkg process when Java exits!
            AppLogger.log("[AutoUpdater] Executing DEB installer via systemd-run...");
            Runtime.getRuntime().exec(new String[]{"sudo", "systemd-run", "/bin/bash", scriptPath});
            
            // Allow the systemd-run command a moment to dispatch
            Thread.sleep(1000);
            
            AppLogger.log("[AutoUpdater] Shutting down application for DEB package upgrade.");
            System.exit(0);

        } catch (Exception e) {
            AppLogger.log("[AutoUpdater] Update application failed: " + e.getMessage());
        }
    }
}
