package com.musicplayer.scamusica.service;

import com.musicplayer.scamusica.util.AppLogger;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class NetworkMonitor {

    private static NetworkMonitor instance;

    private int failureCount = 0;
    private static final int FAILURE_THRESHOLD = 2;

    private final BooleanProperty online = new SimpleBooleanProperty(false);

    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    private static final String PING_URL = "https://api.scamusica.com/";
    private static final int TIMEOUT_MS = 8000;
    private static final int CHECK_INTERVAL_SEC = 15;

    private NetworkMonitor() {}

    public static NetworkMonitor getInstance() {
        if (instance == null) {
            instance = new NetworkMonitor();
        }
        return instance;
    }

    public BooleanProperty onlineProperty() {
        return online;
    }

    public boolean isOnline() {
        return online.get();
    }

    public void start() {
        if (running) return;
        running = true;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NetworkMonitor");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(
                this::checkConnectivity,
                0,
                CHECK_INTERVAL_SEC,
                TimeUnit.SECONDS
        );

        AppLogger.log("[NetworkMonitor] Started");
    }

    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        AppLogger.log("[NetworkMonitor] Stopped");
    }

    private void checkConnectivity() {
        try {
            boolean result = pingServer();

            if (result) {
                failureCount = 0;
            } else {
                failureCount++;
                if (failureCount < FAILURE_THRESHOLD) {
                    AppLogger.log("[NetworkMonitor] Ping failed (" + failureCount + "/" + FAILURE_THRESHOLD + "), waiting...");
                    return;
                }
            }

            Platform.runLater(() -> {
                if (online.get() != result) {
                    online.set(result);
                    AppLogger.log("[NetworkMonitor] Status changed → " + (result ? "ONLINE" : "OFFLINE"));
                }
            });
        } catch (Throwable t) {
            AppLogger.log("[NetworkMonitor] Error in checkConnectivity: " + t.getMessage());
        }
    }

    private boolean pingServer() {
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(PING_URL))
                    .timeout(java.time.Duration.ofMillis(TIMEOUT_MS))
                    .header("User-Agent", "ScamusicaPlayer/1.0")
                    .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody())
                    .build();
            
            java.net.http.HttpResponse<Void> response = java.net.http.HttpClient.newHttpClient()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
            
            int responseCode = response.statusCode();
            return (responseCode >= 200 && responseCode < 400);
        } catch (Exception e) {
            AppLogger.log("[NetworkMonitor] pingServer Exception: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return false;
        }
    }
}