package com.musicplayer.scamusica.service;

import com.musicplayer.scamusica.model.Ad;
import com.musicplayer.scamusica.util.AppLogger;
import com.musicplayer.scamusica.util.Utility;
import javafx.application.Platform;
import com.musicplayer.scamusica.service.CvlcAudioPlayer;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;

public class AdPlayer {

    public interface AdPlaybackListener {
        void onAdPlaybackStarted(Ad ad, com.musicplayer.scamusica.model.AdAudio adAudio);

        void onAdPlaybackFinished(Ad ad);

        void onSongPaused(String reason);

        void onSongResumed();

        void onPlaybackError(Exception ex);
    }

    private final CvlcAudioPlayer audioPlayer;
    private final AdPlaybackListener listener;
//    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AdPlayer-Thread");
        t.setDaemon(true);
        return t;
    });
    private final Queue<Ad> adQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean isPlayingAd = false;
    private volatile boolean songPausedForAds = false;

    private volatile String savedSongPath = null;
    private volatile long savedSongTime = 0L;
    private java.util.function.Supplier<Integer> adVolumeSupplier;
    private volatile int savedMusicVol = 100;

    public AdPlayer(CvlcAudioPlayer audioPlayer, AdPlaybackListener listener) {
        this.audioPlayer = audioPlayer;
        this.listener = listener;
    }

    public void setAdVolumeSupplier(java.util.function.Supplier<Integer> adVolumeSupplier) {
        this.adVolumeSupplier = adVolumeSupplier;
    }

    public void queueAds(List<Ad> ads) {
        if (ads == null || ads.isEmpty()) return;

        AppLogger.log("[AdPlayer] Queueing " + ads.size() + " ads");

        List<Ad> playableAds = new ArrayList<>();

        for (Ad ad : ads) {
            if (AdDownloadManager.isAdDownloaded(ad)) {
                playableAds.add(ad);
                AppLogger.log("[AdPlayer] Ad queued (local): " + ad.getCampaignName());
            } else if (NetworkMonitor.getInstance().isOnline()) {
                playableAds.add(ad);
                AppLogger.log("[AdPlayer] Ad queued (stream): " + ad.getCampaignName());
            } else {
                AppLogger.log("[AdPlayer] Skipping ad (offline + not downloaded): " + ad.getCampaignName());
            }
        }

        if (playableAds.isEmpty()) return;

        AppLogger.log("[AdPlayer] Queueing " + playableAds.size() + " playable ads");
        List<Ad> shuffled = new ArrayList<>(playableAds);
        Collections.shuffle(shuffled);
        adQueue.addAll(shuffled);

        if (!isPlayingAd) {
            isPlayingAd = true;
            songPausedForAds = false;
            playNextAd();
        }
    }

    private void playNextAd() {
        Ad nextAd = adQueue.poll();
        if (nextAd == null) {
            AppLogger.log("[AdPlayer] Queue empty, resuming song");
            isPlayingAd = false;
            songPausedForAds = false;
            resumeSong();
            return;
        }

        executor.submit(() -> {
            try {
                playAdInternal(nextAd);
            } catch (Exception e) {
                AppLogger.log("[AdPlayer] Error: " + e.getMessage());
                listener.onPlaybackError(e);
                playNextAd();
            }
        });
    }

    public long getSavedSongTime() {
        return savedSongTime;
    }

    private void playAdInternal(Ad ad) throws Exception {
        AppLogger.log("[AdPlayer] Preparing ad: " + ad.getCampaignName());

        List<String> validUrls = new ArrayList<>();
        List<com.musicplayer.scamusica.model.AdAudio> validAudios = new ArrayList<>();

        if (ad.getAdAudios() != null && !ad.getAdAudios().isEmpty()) {
            for (com.musicplayer.scamusica.model.AdAudio adAudio : ad.getAdAudios()) {
                String adUrl = buildAdUrl(adAudio);
                if (adUrl != null) {
                    validUrls.add(adUrl);
                    validAudios.add(adAudio);
                } else {
                    AppLogger.log("[AdPlayer] Invalid or offline ad audio URL, skipping");
                }
            }
        }

        if (validUrls.isEmpty()) {
            AppLogger.log("[AdPlayer] No playable audios found for ad: " + ad.getCampaignName() + ", skipping completely");
            Platform.runLater(() -> listener.onAdPlaybackFinished(ad));
            playNextAd();
            return;
        }

        if (!songPausedForAds) {
            // Step 1: Save current song state
            final long[] timeRef = {0L};
            final int[] volRef = {100};
            CountDownLatch stateLatch = new CountDownLatch(1);
            Platform.runLater(() -> {
                try {
                    timeRef[0] = audioPlayer.getTime();
                } catch (Exception ignored) {}
                try {
                    volRef[0] = audioPlayer.getVolume();
                } catch (Exception ignored) {}
                stateLatch.countDown();
            });
            try {
                stateLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                AppLogger.log("[AdPlayer] State fetch interrupted");
            }
            savedSongTime = timeRef[0];
            savedMusicVol = volRef[0];
            int originalVol = volRef[0];
            try {
                int steps = 20;
                for (int i = 0; i < steps; i++) {
                    if (!isPlayingAd) break;
                    int currentVol = (int) (originalVol * (1.0 - (double) i / steps));
                    Platform.runLater(() -> {
                        try {
                            audioPlayer.setVolume(currentVol);
                        } catch (Exception ignored) {}
                    });
                    Thread.sleep(100);
                }
                Platform.runLater(() -> {
                    try {
                        audioPlayer.setVolume(0);
                    } catch (Exception ignored) {}
                });
            } catch (Exception e) {
            }

            // Step 2: Stop current song
            Platform.runLater(() -> {
                try {
                    savedSongTime = audioPlayer.getTime();
                    AppLogger.log("[AdPlayer] Saving song position: " + savedSongTime);
                    audioPlayer.pause();
                    listener.onSongPaused("Ad starting");
                } catch (Exception ignored) {
                }
            });
            Thread.sleep(600);
            songPausedForAds = true;
            
            // Restore volume for the ad AFTER the song is completely paused
            try {
                int adVol = adVolumeSupplier != null ? adVolumeSupplier.get() : originalVol;
                AppLogger.log("[AdPlayer] Volume updated to ad volume: " + adVol);
                audioPlayer.setVolume(adVol);
            } catch (Exception ignored) {}
        }

        // Step 3: Loop over valid ad audios
        for (int i = 0; i < validUrls.size(); i++) {
            String adUrl = validUrls.get(i);
            com.musicplayer.scamusica.model.AdAudio adAudio = validAudios.get(i);

            AppLogger.log("[AdPlayer] Playing ad from URL: " + adUrl);

            Platform.runLater(() -> {
                listener.onAdPlaybackStarted(ad, adAudio);
            });

            try {
                AppLogger.log("[AdPlayer] STARTING ACTUAL VLC PLAY");
                audioPlayer.playAndWait(adUrl);
                AppLogger.log("[AdPlayer] VLC PLAY FINISHED");
            } catch (Exception e) {
                AppLogger.log("[AdPlayer] Failed to start ad audio: " + e.getMessage());
            }

            Thread.sleep(300); // Minor delay between consecutive audios
        }

        // Step 6: Ad done, notify
        Platform.runLater(() -> listener.onAdPlaybackFinished(ad));

        // Step 7: Play next ad or resume song
        playNextAd();
    }

    private void resumeSong() {
        Platform.runLater(() -> {
            try {
                listener.onSongResumed();
            } catch (Exception e) {
                AppLogger.log("[AdPlayer] Resume error: " + e.getMessage());
            }
        });
    }

    private String buildAdUrl(com.musicplayer.scamusica.model.AdAudio adAudio) {
        if (adAudio == null) return null;

        File localFile = AdDownloadManager.getLocalAdFile(adAudio);
        if (localFile != null && localFile.exists() && localFile.length() > 1024) {
            AppLogger.log("[AdPlayer] Playing ad from local file: " + localFile.getAbsolutePath());
            return localFile.getAbsolutePath();
        }

        if (!NetworkMonitor.getInstance().isOnline()) {
            AppLogger.log("[AdPlayer] Ad not downloaded and offline, skipping: ad-audio-" + adAudio.getId());
            return null;
        }

        String audioFile = adAudio.getAudioFile();
        if (audioFile == null || audioFile.isEmpty()) return null;

        if (audioFile.startsWith("http://") || audioFile.startsWith("https://")) {
            return audioFile;
        }

        String encoded = audioFile
                .replace(" ", "%20")
                .replace("(", "%28")
                .replace(")", "%29")
                .replace("[", "%5B")
                .replace("]", "%5D");
        if (!encoded.startsWith("/")) {
            encoded = "/" + encoded;
        }

        return Utility.FILEPATH_BASE_URL.get() + encoded;
    }

    public boolean isPlayingAd() {
        return isPlayingAd;
    }

    public void clearQueue() {
        adQueue.clear();
        AppLogger.log("[AdPlayer] Queue cleared");
    }

    public void stop() {
        clearQueue();
        isPlayingAd = false;
        executor.shutdownNow();
        AppLogger.log("[AdPlayer] Stopped");
    }
}