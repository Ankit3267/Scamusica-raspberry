package com.musicplayer.scamusica.controller;

import com.musicplayer.scamusica.manager.LanguageManager;
import com.musicplayer.scamusica.model.Ad;
import com.musicplayer.scamusica.model.PlaylistTrack;
import com.musicplayer.scamusica.service.*;
import com.musicplayer.scamusica.ui.*;

import com.musicplayer.scamusica.util.AppLogger;
import com.musicplayer.scamusica.util.CryptoUtil;
import com.musicplayer.scamusica.util.PlaybackHistoryLogger;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;

import com.musicplayer.scamusica.service.CvlcAudioPlayer;

import javax.crypto.CipherInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

import com.musicplayer.scamusica.util.OfflineCache;

public class PlayerController extends Application {

    private Label globalAlbumHeading;
    private Label globalTitleLabel;

    private Slider globalProgressSlider;
    private HBox globalControlsWrapper;
    private HBox globalBottomBar;
    private Label globalDownloadLabel;
    private Label globalLeftTime;
    private Label globalRightTime;

    private CvlcAudioPlayer audioPlayer;
    private boolean vlcHandlersAttached = false;
    private boolean userPaused = false;

    private CvlcAudioPlayer.EventListener currentVlcListener = null;
    private File currentTempFile = null;
    private Thread queueWorkerThread = null;

    private final List<String> playlistMaster = new ArrayList<>();
    private final javafx.collections.ObservableList<String> playlistViewItems = FXCollections.observableArrayList();
    private final String[] playlistCurrent = new String[1];

    private final PlayerSidebar sidebarUtil = new PlayerSidebar();
    private final PlayerHeader headerUtil = new PlayerHeader();
    private final PlayerDropdown dropdownUtil = new PlayerDropdown();
    private final PlayerControls controlsUtil = new PlayerControls();
    private final PlayerAlbum albumUtil = new PlayerAlbum();

    private VBox playlistDropdownCard;
    private HBox playlistPill;

    private String currentPlaylistName;

    private static final String PREF_VOLUME = "player_volume";
    private static final String PREF_RESUME_PLAYLIST = "resume_playlist";
    private static final String PREF_RESUME_TRACK_ID = "resume_track_id";
    private static final String PREF_RESUME_TIME = "resume_time";
    private static final String SONGS_DIR = System.getProperty("user.home")
            + File.separator + ".scamusica"
            + File.separator + "songs";
    private final Preferences prefs = Preferences.userNodeForPackage(PlayerController.class);

    private final List<PlaylistTrack> playQueue = Collections.synchronizedList(new ArrayList<>());
    private int currentTrackIndex = 0;

    private volatile boolean isFirstTrackStarted = false;
    private int consecutiveErrorCount = 0;

    private ImageView albumImageView;
    private String currentAlbumImgUrl = null;
    private Image defaultAlbumImage = null;

    private void setDefaultAlbumImage() {
        if (albumImageView != null) {
            Platform.runLater(() -> albumImageView.setImage(defaultAlbumImage));
        }
    }

    private final List<String> tempPlaylist = Arrays.asList(
            "Secuencias-Estilos-playlist",
            "Sequence 1",
            "Sequence 2",
            "Sequence 3",
            "Playlist Custom 1",
            "Playlist Custom 2",
            "Playlist Custom 3");

    private DownloadManager downloadManager;

    private final AtomicInteger totalDownloadedCounter = new AtomicInteger(0);

    private volatile int currentGenreTotalFiles = 0;
    private final AtomicInteger currentGenreDownloadedCount = new AtomicInteger(0);

    private final PlaylistApiService apiService = new PlaylistApiService();
    private volatile double currentFileProgressFraction = 0.0;

    private ScheduledExecutorService schedular;
    private BlockingQueue<Runnable> operationQueue = new LinkedBlockingQueue<>();
    private java.util.concurrent.ExecutorService asyncExecutor = java.util.concurrent.Executors.newFixedThreadPool(4,
            r -> {
                Thread t = new Thread(r, "AsyncExecutor-Thread");
                t.setDaemon(true);
                return t;
            });
    private volatile boolean running = true;
    private List<Integer> lastServerIds = new ArrayList<>();
    private List<Integer> currentDownloadSequence = new ArrayList<>();
    private java.util.Map<Integer, String> idToStyleMap = new java.util.HashMap<>();

    private AdScheduler adScheduler;
    private AdPlayer adPlayer;
    private List<Ad> allAds = new ArrayList<>();

    private com.musicplayer.scamusica.model.VolumeSettings volumeSettings;
    private Integer currentScheduleId = null;
    private int currentAdVolume = 100;
    private boolean isFirstVolumeApply = true;

    private void migrateFromSequenceFolders() {
        try {
            File oldDownloadsDir = new File(System.getProperty("user.home") 
                + File.separator + ".scamusica" + File.separator + "downloads");
            File newSongsDir = new File(SONGS_DIR);
            
            if (!oldDownloadsDir.exists()) return;
            if (!newSongsDir.exists()) newSongsDir.mkdirs();
            
            File[] folders = oldDownloadsDir.listFiles();
            if (folders == null) return;
            
            int migratedCount = 0;
            for (File folder : folders) {
                if (!folder.isDirectory()) continue;
                File[] songs = folder.listFiles();
                if (songs == null) continue;
                for (File song : songs) {
                    if (song.getName().startsWith("song-") && song.getName().endsWith(".dat")) {
                        File target = new File(newSongsDir, song.getName());
                        if (!target.exists()) {
                            if (song.renameTo(target)) {
                                migratedCount++;
                            } else {
                                java.nio.file.Files.copy(song.toPath(), target.toPath());
                                song.delete();
                                migratedCount++;
                            }
                        } else {
                            song.delete();
                        }
                    }
                }
                folder.delete();
            }
            
            File[] remaining = oldDownloadsDir.listFiles();
            if (remaining == null || remaining.length == 0) {
                oldDownloadsDir.delete();
            }
            
            if (migratedCount > 0) {
                AppLogger.log("[MIGRATION] Migrated " + migratedCount + " songs to shared songs folder");
            }
        } catch (Exception e) {
            AppLogger.log("[MIGRATION] Error: " + e.getMessage());
        }
    }

    @Override
    public void start(Stage primaryStage) {

        AppLogger.init();
        migrateFromSequenceFolders();
        // === TEMP CLEANUP ===
        try {
            File tempDir = new File(System.getProperty("user.home")
                    + File.separator + ".scamusica"
                    + File.separator + "temp");
            if (tempDir.exists() && tempDir.isDirectory()) {
                File[] files = tempDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().startsWith("play_") && f.getName().endsWith(".mp3")) {
                            f.delete();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // === NETWORK MONITOR START ===
        NetworkMonitor.getInstance().start();
        AppLogger.log("[APP] Player started");

        // Start heartbeat service
        com.musicplayer.scamusica.service.HeartbeatService.getInstance().start();

        // Start log sync service
        com.musicplayer.scamusica.service.LogSyncService.getInstance().start();

        // Start memory watchdog
        MemoryWatchdog.getInstance().start();

        audioPlayer = new CvlcAudioPlayer();

        initializeAdSystem();

        Button headphonesButton = sidebarUtil.createIconButton("fas-headphones");
        List<Button> sidebarButtons = Arrays.asList(headphonesButton);
        sidebarUtil.addSidebarLogic(sidebarButtons, headphonesButton);
        VBox sidebarTop = sidebarUtil.createSidebarTop(headphonesButton);
        FontIcon settingsIcon = sidebarUtil.createSettingsIcon(primaryStage, () -> {
            shutdownApp();
        });
        VBox sidebar = sidebarUtil.createSidebar(sidebarTop, settingsIcon);

        HBox leftMeta = headerUtil.createLeftMeta();
        ImageView logoView = headerUtil.createLogoView(getClass());
        HBox rightMeta = headerUtil.createRightMeta();
        ComboBox<LangItem> languageBox = LanguageManager.createLanguageSelector();
        languageBox.getStyleClass().add("language-selector");
        rightMeta.getChildren().add(languageBox);
        BorderPane header = headerUtil.createHeader(leftMeta, logoView, rightMeta);

        globalAlbumHeading = albumUtil.createAlbumHeading();
        Label albumHeading = globalAlbumHeading;

        Label currentStyleLabel = new Label();
        currentStyleLabel.textProperty().bind(
                LanguageManager.createStringBinding("label.currentStyle"));

        currentStyleLabel.getStyleClass().add("section-heading-styles");

        ImageView img = albumUtil.createAlbumImage(getClass());
        albumImageView = img;
        defaultAlbumImage = img.getImage();
        albumUtil.applyClip(img);
        HBox songsBox = albumUtil.createSongsBox();

        VBox leftAlbumVBox = albumUtil.createLeftAlbumVBox(albumHeading, img, songsBox);
        leftAlbumVBox.getChildren().add(0, currentStyleLabel);

        MemoryWatchdog.getInstance().registerCleanupCallback(() -> {
            // Hint to JVM that large image buffers can be collected
            Platform.runLater(() -> {
                if (albumImageView != null && albumImageView.getImage() != null) {
                    albumImageView.getImage().cancel();
                }
            });
        });

        MemoryWatchdog.getInstance().registerPreRestartCallback(() -> {
            try {
                if (currentPlaylistName != null) {
                    prefs.put(PREF_RESUME_PLAYLIST, currentPlaylistName);
                }
                if (!playQueue.isEmpty() && currentTrackIndex < playQueue.size() && currentTrackIndex >= 0) {
                    prefs.putInt(PREF_RESUME_TRACK_ID, playQueue.get(currentTrackIndex).getId());
                } else {
                    prefs.remove(PREF_RESUME_TRACK_ID);
                }
                if (audioPlayer != null) {
                    long time = 0;
                    try {
                        time = audioPlayer.getTime();
                    } catch (Exception e) {
                    }
                    if (adPlayer != null && adPlayer.isPlayingAd()) {
                        time = adPlayer.getSavedSongTime();
                    }
                    prefs.putLong(PREF_RESUME_TIME, time);
                }
                prefs.flush();
                AppLogger.log("[PlayerController] Saved resume state: Playlist=" + currentPlaylistName + ", Time="
                        + prefs.getLong(PREF_RESUME_TIME, 0));
            } catch (Exception e) {
                AppLogger.log("[PlayerController] Error saving resume state: " + e.getMessage());
            }
        });

        recomputeGlobalCountAndUpdateUI();

        List<String> tempList;
        try {
            PlaylistApiService playlistApiService = new PlaylistApiService();
            List<String> apiPlaylists = playlistApiService.fetchPlaylistTitles();
            if (apiPlaylists != null && !apiPlaylists.isEmpty()) {
                tempList = new ArrayList<>(apiPlaylists);
            } else {
                tempList = tempPlaylist;
                AppLogger.log("[PlayerController] API returned empty list, using fallback playlists.");
            }
        } catch (Exception e) {
            e.printStackTrace();
            AppLogger.log("[PlayerController] API failed, checking offline cache...");
            List<String> cached = OfflineCache.loadPlaylistTitles();
            if (!cached.isEmpty()) {
                tempList = cached;
                AppLogger.log("[PlayerController] Using cached playlists: " + cached.size());
            } else {
                tempList = tempPlaylist; // hardcoded fallback
                AppLogger.log("[PlayerController] No cache, using hardcoded fallback.");
            }
        }

        playlistMaster.addAll(tempList);

        String savedPlaylist = prefs.get(PREF_RESUME_PLAYLIST, null);
        if (savedPlaylist != null && playlistMaster.contains(savedPlaylist)) {
            playlistCurrent[0] = savedPlaylist;
        } else {
            playlistCurrent[0] = playlistMaster.get(0);
        }

        playlistViewItems.setAll(
                playlistMaster.stream()
                        .filter(s -> !s.equals(playlistCurrent[0]))
                        .collect(Collectors.toList()));

        playlistPill = dropdownUtil.createPlaylistPill(playlistCurrent[0]);
        playlistDropdownCard = dropdownUtil.createDropdownCard(playlistViewItems, playlistCurrent, playlistMaster,
                playlistPill);

        HBox playlistHeaderBox = dropdownUtil.createPlaylistHeaderBox(playlistPill);

        // New Code
        Label sequencesLabel = new Label();
        sequencesLabel.textProperty().bind(
                LanguageManager.createStringBinding("label.sequencesTitle"));
        sequencesLabel.getStyleClass().add("section-heading-sequences");

        // New Code
        VBox rightColumn = new VBox(8);
        rightColumn.getChildren().addAll(sequencesLabel, playlistHeaderBox);

        HBox rightWrapper = new HBox(rightColumn);
        rightWrapper.setAlignment(Pos.TOP_RIGHT);

        globalTitleLabel = headerUtil.createPlayerTitle();
        Label titleCentered = globalTitleLabel;
        VBox centerContainer = headerUtil.createCenterContainer(titleCentered);
        HBox topRow = albumUtil.createTopRow(leftAlbumVBox, centerContainer, rightWrapper);

        Slider progressSlider = controlsUtil.createProgressSlider();
        globalProgressSlider = progressSlider;

        Label leftTime = controlsUtil.createTimeLabel(false);
        globalLeftTime = leftTime;
        Label rightTime = controlsUtil.createTimeLabel(true);
        globalRightTime = rightTime;
        HBox timesRow = controlsUtil.createTimesRow(leftTime, rightTime);
        HBox progressRow = controlsUtil.createProgressRow(progressSlider);
        VBox sliderContainer = controlsUtil.createSliderContainer(titleCentered, timesRow, progressRow);
        HBox controlsWrapper = controlsUtil.createControls(progressSlider, playlistPill);
        globalControlsWrapper = controlsWrapper;

        HBox bottomBar = controlsUtil.createBottomBar();
        globalBottomBar = bottomBar;

        Label downloadLabel = controlsUtil.getDownloadLabel(bottomBar);
        globalDownloadLabel = downloadLabel;

        if (downloadLabel != null) {
            bottomBar.getChildren().remove(downloadLabel);
            downloadLabel.setPadding(new javafx.geometry.Insets(6, 0, 0, 6));
            downloadLabel.getStyleClass().remove("download-label");
            downloadLabel.getStyleClass().add("download-label-left");
            leftAlbumVBox.getChildren().add(downloadLabel);
        }

        StackPane combinedBottom = new StackPane();
        bottomBar.setPickOnBounds(false);
        controlsWrapper.setPickOnBounds(false);
        combinedBottom.getChildren().addAll(bottomBar, controlsWrapper);
        StackPane.setAlignment(bottomBar, Pos.CENTER);

        VBox contentVBox = new VBox();
        contentVBox.setSpacing(0);
        contentVBox.getChildren().addAll(header, topRow, sliderContainer, combinedBottom);
        VBox.setVgrow(topRow, Priority.ALWAYS);

        BorderPane mainPane = new BorderPane();
        mainPane.getStyleClass().add("root");
        mainPane.setLeft(sidebar);
        mainPane.setCenter(contentVBox);

        Pane rootOverlay = new Pane();
        mainPane.setPrefSize(1200, 720);
        mainPane.prefWidthProperty().bind(rootOverlay.widthProperty());
        mainPane.prefHeightProperty().bind(rootOverlay.heightProperty());

        rootOverlay.getChildren().addAll(mainPane, playlistDropdownCard);

        Scene scene = new Scene(rootOverlay, 1200, 720);
        scene.getStylesheets().add(getClass().getResource("/styles/style.css").toExternalForm());
        playlistDropdownCard.getStylesheets().add(scene.getStylesheets().get(0));

        headerUtil.loadFonts(getClass());
        scene.getRoot().setStyle("-fx-font-family: 'Poppins', 'Noto Sans', 'Noto Sans JP', 'Noto Sans SC', 'Noto Sans" +
                " Arabic', 'Noto Sans Devanagari';");
        albumUtil.bindImageSize(img, scene, progressRow, titleCentered);

        dropdownUtil.setupDropdownHandlers(
                playlistPill,
                playlistDropdownCard,
                playlistMaster,
                playlistViewItems,
                playlistCurrent,
                scene,
                rootOverlay,
                img,
                this::hideDropdown,
                selectedPlaylistName -> {
                    currentPlaylistName = selectedPlaylistName;
                    prefs.remove(PREF_RESUME_PLAYLIST);
                    prefs.remove(PREF_RESUME_TRACK_ID);
                    prefs.remove(PREF_RESUME_TIME);
                    try {
                        loadPlaylistAndStart(
                                selectedPlaylistName,
                                albumHeading,
                                titleCentered,
                                progressSlider,
                                leftTime,
                                rightTime,
                                controlsWrapper,
                                bottomBar,
                                downloadLabel,
                                true);
                    } catch (URISyntaxException e) {
                        throw new RuntimeException(e);
                    }
                });

        primaryStage.titleProperty().bind(
                LanguageManager.createStringBinding("app.title"));
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(1150);
        primaryStage.setMinHeight(650);

        primaryStage.setOnCloseRequest(event -> {
            event.consume();
            String lang = com.musicplayer.scamusica.manager.LanguageManager.getLangCode();
            String title = "es".equals(lang) ? "¿Está seguro de cerrar la App?" : "Are you sure you want to close the App?";
            String subtitle = "es".equals(lang) ? "Se detendrá la reproducción de música" : "Music playback will stop";

            new LeavingPopup().show(primaryStage, title, subtitle, new LeavingPopup.Callback() {
                @Override
                public void onYes() {
                    shutdownApp();
                    javafx.application.Platform.exit();
                    System.exit(0);
                }

                @Override
                public void onNo() {
                }
            });
        });

        primaryStage.show();

        Platform.runLater(() -> {
            controlsUtil.setupSliderFill(progressSlider);

            Slider volumeSlider = controlsUtil.getVolumeSlider(bottomBar);
            controlsUtil.setupVolumeSliderFill(controlsUtil.getVolumeSlider(bottomBar));

            double savedVolume = prefs.getDouble(PREF_VOLUME, 85.0);
            volumeSlider.setValue(savedVolume);
            audioPlayer.setVolume((int) savedVolume);

            volumeSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (adPlayer != null && adPlayer.isPlayingAd()) {
                    audioPlayer.setVolume(newVal.intValue());
                    return;
                }
                prefs.putDouble(PREF_VOLUME, newVal.doubleValue());
                audioPlayer.setVolume(newVal.intValue());
            });

            progressSlider.setOnMouseReleased(e -> {
                float pos = (float) (progressSlider.getValue() / 100.0);
                audioPlayer.seek((float) pos);
            });

            currentPlaylistName = playlistCurrent[0];
            try {
                loadPlaylistAndStart(
                        playlistCurrent[0],
                        albumHeading,
                        titleCentered,
                        progressSlider,
                        leftTime,
                        rightTime,
                        controlsWrapper,
                        bottomBar,
                        downloadLabel,
                        true);

                setupBigPlayBehaviour(
                        albumHeading,
                        titleCentered,
                        controlsWrapper,
                        progressSlider,
                        leftTime,
                        rightTime,
                        bottomBar,
                        downloadLabel);

            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }

            Button forwardBtn = null;
            HBox forwardBox = null;
            try {
                forwardBox = (HBox) controlsWrapper.getChildren().get(2);
                forwardBtn = (Button) forwardBox.getChildren().get(0);
            } catch (Exception ex) {
                forwardBtn = (Button) controlsWrapper.lookup("#forwardButton");
            }

            if (forwardBtn != null) {
                final Button finalForwardBtn = forwardBtn;
                javafx.event.EventHandler<javafx.scene.input.MouseEvent> fwdHandler = e -> {
                    e.consume();
                    finalForwardBtn.getStyleClass().add("control-active");
                    Platform.runLater(() -> finalForwardBtn.getStyleClass().remove("control-active"));

                    try {
                        playNextTrack(
                                albumHeading,
                                titleCentered,
                                progressSlider,
                                leftTime,
                                rightTime,
                                controlsWrapper,
                                bottomBar,
                                downloadLabel);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                };

                finalForwardBtn.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, fwdHandler);
                if (forwardBox != null) {
                    forwardBox.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, fwdHandler);
                }
            }
        });

        // 🔥 START QUEUE WORKER
        queueWorkerThread = new Thread(() -> {
            while (running) {
                try {
                    Runnable task = operationQueue.take();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        queueWorkerThread.setDaemon(true);
        queueWorkerThread.start();

        // 🔥 START SCHEDULER
        schedular = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SyncScheduler");
            t.setDaemon(true);
            return t;
        });

        schedular.scheduleAtFixedRate(() -> {
            operationQueue.add(() -> {
                if (!NetworkMonitor.getInstance().isOnline()) {
                    AppLogger.log("[SYNC] Offline — skipping server sync");
                    return;
                }
                try {
                    syncWithServer();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }, 30, 300, java.util.concurrent.TimeUnit.SECONDS); // 300s = 5 minutes

        initVolumeScheduler();
    }

    private void initVolumeScheduler() {
        try {
            volumeSettings = apiService.fetchVolumeSettings();
            if (volumeSettings != null) {
                currentAdVolume = volumeSettings.getAdVolume() != null ? volumeSettings.getAdVolume() : 100;
            }
        } catch (Exception e) {
            AppLogger.log("[PlayerController] Failed to fetch initial volume settings: " + e.getMessage());
        }

        Runnable volumeTask = () -> {
            try {
                if (volumeSettings == null)
                    return;

                java.time.LocalTime now = java.time.LocalTime.now();
                com.musicplayer.scamusica.model.VolumeSchedule activeSchedule = null;

                if (volumeSettings.getSchedules() != null) {
                    for (com.musicplayer.scamusica.model.VolumeSchedule schedule : volumeSettings.getSchedules()) {
                        try {
                            java.time.LocalTime start = java.time.LocalTime.parse(schedule.getStartTime());
                            java.time.LocalTime end = java.time.LocalTime.parse(schedule.getEndTime());
                            if (!now.isBefore(start) && now.isBefore(end)) {
                                activeSchedule = schedule;
                                break;
                            }
                        } catch (Exception ex) {
                            AppLogger.log("[VolumeScheduler] Failed to parse schedule time: " + ex.getMessage());
                        }
                    }
                }

                if (adPlayer != null && adPlayer.isPlayingAd()) {
                    return; // Skip applying volume adjustments during ad playback
                }

                Integer targetScheduleId = activeSchedule != null ? activeSchedule.getId() : null;

                final boolean isVolumeFromAdmin = volumeSettings.getMusicVolume() != null &&
                        volumeSettings.getVolumeSource() != null &&
                        ("admin".equalsIgnoreCase(volumeSettings.getVolumeSource()) || "player".equalsIgnoreCase(volumeSettings.getVolumeSource()));
                final boolean isScheduleActive = activeSchedule != null;
                final boolean shouldDisableSlider = isVolumeFromAdmin || isScheduleActive;

                Platform.runLater(() -> {
                    try {
                        if (globalBottomBar != null) {
                            Slider volumeSlider = controlsUtil.getVolumeSlider(globalBottomBar);
                            if (volumeSlider != null) {
                                volumeSlider.setDisable(shouldDisableSlider);
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });

                int newMusicVolume = activeSchedule != null && activeSchedule.getMusicVolume() != null
                        ? activeSchedule.getMusicVolume()
                        : (volumeSettings.getMusicVolume() != null ? volumeSettings.getMusicVolume() : 100);
                int newAdVolume = activeSchedule != null && activeSchedule.getAdVolume() != null
                        ? activeSchedule.getAdVolume()
                        : (volumeSettings.getAdVolume() != null ? volumeSettings.getAdVolume() : 100);
                
                int currentSetVol = (int) prefs.getDouble(PREF_VOLUME, 100.0);
                boolean volumeNeedsUpdate = (newMusicVolume != currentSetVol);
                boolean scheduleChanged = isFirstVolumeApply || !java.util.Objects.equals(currentScheduleId, targetScheduleId);

                if (scheduleChanged || (shouldDisableSlider && volumeNeedsUpdate)) {
                    currentScheduleId = targetScheduleId;
                    isFirstVolumeApply = false;

                    currentAdVolume = newAdVolume;

                    AppLogger.log("[VolumeScheduler] Applying new volume settings: Music=" + newMusicVolume + ", Ad="
                            + newAdVolume);
                    AppLogger.log("[VolumeScheduler] Song volume updated to: " + newMusicVolume);
                    AppLogger.log("[VolumeScheduler] Ad volume updated to: " + newAdVolume);

                    Platform.runLater(() -> {
                        prefs.putDouble(PREF_VOLUME, newMusicVolume);
                        if (audioPlayer != null) {
                            audioPlayer.setVolume(newMusicVolume);
                        }
                        if (globalBottomBar != null) {
                            Slider volumeSlider = controlsUtil.getVolumeSlider(globalBottomBar);
                            if (volumeSlider != null) {
                                volumeSlider.setValue(newMusicVolume);
                            }
                        }
                    });
                }

            } catch (Exception e) {
                AppLogger.log("[VolumeScheduler] Error in schedule loop: " + e.getMessage());
            }
        };

        long initialDelaySeconds = 60 - java.time.LocalTime.now().getSecond();
        schedular.scheduleAtFixedRate(volumeTask, initialDelaySeconds, 60, java.util.concurrent.TimeUnit.SECONDS);
        // Also run it immediately once on startup
        schedular.schedule(volumeTask, 2, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void syncWithServer() {
        if (!running)
            return;

        PlaylistApiService apiService = null;
        try {
            apiService = new PlaylistApiService();

            if (!NetworkMonitor.getInstance().isOnline())
                return;

            try {
                syncAdsFromServer(apiService);
            } catch (Exception e) {
                AppLogger.log("[SYNC] syncAdsFromServer failed: " + e.getMessage());
            }

            // ✅ Playlist titles sync with Auto-Switch
            try {
                List<String> fetchedTitles = apiService.fetchPlaylistTitles();
                List<String> serverTitles;

                if (fetchedTitles == null) {
                    serverTitles = new ArrayList<>(java.util.Arrays.asList("Default"));
                    AppLogger.log("[SYNC] API returned null titles, falling back to Default sequence.");
                } else if (fetchedTitles.isEmpty()) {
                    AppLogger.log("[SYNC] API returned empty titles (No sequence assigned).");
                    serverTitles = new ArrayList<>();
                } else {
                    serverTitles = fetchedTitles;
                }

                if (serverTitles != null) {
                    if (serverTitles.isEmpty()) {
                        AppLogger.log("[SYNC] No sequence assigned. Clearing player and downloads.");
                        
                        final List<String> emptyList = new ArrayList<>();
                        asyncExecutor.submit(() -> {
                            try {
                                cleanupOrphanedSongs(emptyList);
                            } catch (Exception e) {
                                AppLogger.log("[SYNC] Orphaned sequence cleanup failed: " + e.getMessage());
                            }
                        });

                        Platform.runLater(() -> {
                            try {
                                if (downloadManager != null) {
                                    downloadManager.stop();
                                    downloadManager = null;
                                }
                                
                                stopPlayback(globalProgressSlider, globalLeftTime, globalRightTime, globalControlsWrapper, globalDownloadLabel);
                                playQueue.clear();
                                lastServerIds.clear();
                                playlistMaster.clear();
                                playlistViewItems.clear();
                                currentPlaylistName = null;
                                
                                if (playlistPill != null) {
                                    Label textLabel = (Label) playlistPill.getChildren().get(0);
                                    textLabel.setText("No Sequence");
                                }
                                
                                globalTitleLabel.setText("No Songs");
                                globalAlbumHeading.setText("No Sequence Assigned");
                                setDefaultAlbumImage();
                                
                                albumUtil.setSongCount(0);
                                totalDownloadedCounter.set(0);
                                currentGenreDownloadedCount.set(0);
                                currentGenreTotalFiles = 0;
                                if (globalDownloadLabel != null) {
                                    updateGenreDownloadLabel(globalDownloadLabel);
                                }
                                
                                prefs.remove(PREF_RESUME_PLAYLIST);
                                prefs.remove(PREF_RESUME_TRACK_ID);
                                prefs.remove(PREF_RESUME_TIME);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        });
                        
                        return; // Stop further sync
                    }

                    List<String> newSequences = serverTitles.stream()
                            .filter(title -> !playlistMaster.contains(title))
                            .collect(Collectors.toList());

                    boolean sequenceSwitched = false;
                    String nextSequence = null;

                    // SCENARIO 1 & 3: New sequence assigned (or first sequence assigned)
                    if (!newSequences.isEmpty()) {
                        nextSequence = newSequences.get(0);
                        AppLogger.log("[SYNC] New sequence(s) added detected! Switching immediately to: " + nextSequence);
                        sequenceSwitched = true;
                    }
                    // SCENARIO 2: Current active sequence was deleted
                    else if (currentPlaylistName != null && !serverTitles.contains(currentPlaylistName)) {
                        if (!serverTitles.isEmpty()) {
                            nextSequence = serverTitles.get(0);
                            AppLogger.log("[SYNC] Current sequence removed detected! Switching to fallback: " + nextSequence);
                            sequenceSwitched = true;
                        }
                    }

                    // Update master playlist list
                    if (!serverTitles.equals(playlistMaster)) {
                        playlistMaster.clear();
                        playlistMaster.addAll(serverTitles);
                        AppLogger.log("[SYNC] Playlist titles updated: " + serverTitles.size());
                    }

                    // ✅ Cleanup orphaned sequence folders (runs on background thread)
                    final List<String> titlesForCleanup = new ArrayList<>(serverTitles);
                    asyncExecutor.submit(() -> {
                        try {
                            cleanupOrphanedSongs(titlesForCleanup);
                        } catch (Exception e) {
                            AppLogger.log("[SYNC] Orphaned sequence cleanup failed: " + e.getMessage());
                        }
                    });

                    // Execute the auto-switch
                    if (sequenceSwitched && nextSequence != null) {
                        currentPlaylistName = nextSequence;
                        playlistCurrent[0] = nextSequence;
                        final String finalNextSeq = nextSequence;

                        // Stop the old download manager BEFORE loading new playlist
                        if (downloadManager != null) {
                            downloadManager.stop();
                            downloadManager = null;
                        }

                        Platform.runLater(() -> {
                            try {
                                // Update UI pill label
                                if (playlistPill != null) {
                                    Label textLabel = (Label) playlistPill.getChildren().get(0);
                                    textLabel.setText(finalNextSeq);
                                }

                                // Clear current queue to force new sequence to load
                                playQueue.clear();

                                // Load and start the new playlist
                                loadPlaylistAndStart(
                                        finalNextSeq,
                                        globalAlbumHeading,
                                        globalTitleLabel,
                                        globalProgressSlider,
                                        globalLeftTime,
                                        globalRightTime,
                                        globalControlsWrapper,
                                        globalBottomBar,
                                        globalDownloadLabel,
                                        true
                                );

                                // Always update dropdown items
                                playlistViewItems.setAll(
                                        playlistMaster.stream()
                                                .filter(s -> !s.equals(playlistCurrent[0]))
                                                .collect(Collectors.toList()));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        });

                        // Sequence switched, so we skip syncing tracks for the old sequence!
                        return;
                    }
                }
            } catch (Exception e) {
                AppLogger.log("[SYNC] Playlist title sync failed: " + e.getMessage());
            }

            // Guard track sync: skip if no playlist is active
            if (currentPlaylistName == null)
                return;

            // ✅ Volume settings sync
            try {
                com.musicplayer.scamusica.model.VolumeSettings newVolumeSettings = apiService.fetchVolumeSettings();
                if (newVolumeSettings != null) {
                    volumeSettings = newVolumeSettings;
                    AppLogger.log("[SYNC] Volume settings updated.");
                }
            } catch (Exception e) {
                AppLogger.log("[SYNC] Volume settings sync failed: " + e.getMessage());
            }

            // ============================================
            // TRACK SYNC FOR CURRENT SEQUENCE
            // ============================================

            List<PlaylistTrack> serverTracks = apiService.fetchTracksForGenre(currentPlaylistName);
            AppLogger.log("[SYNC] Server tracks count: " + (serverTracks != null ? serverTracks.size() : 0));

            if (serverTracks == null)
                return;

            List<Integer> serverDownloadSeq = apiService.fetchDownloadSequenceForGenre(currentPlaylistName);
            if (serverDownloadSeq != null) {
                currentDownloadSequence = new ArrayList<>(serverDownloadSeq);
            }
            
            for (PlaylistTrack t : serverTracks) {
                idToStyleMap.put(t.getId(), t.getFolderTitle());
            }

            String baseDownloadDir = System.getProperty("user.home") + File.separator + ".scamusica" + File.separator + "downloads";
            String genreFolderPath = SONGS_DIR;

            List<Integer> serverIds = serverTracks.stream()
                    .map(PlaylistTrack::getId)
                    .collect(Collectors.toList());

            if (serverIds.equals(lastServerIds)) {
                AppLogger.log("[SYNC] No changes detected");
                return; // no change
            }

            lastServerIds = new ArrayList<>(serverIds);

            List<Integer> localIds;
            synchronized (playQueue) {
                localIds = playQueue.stream()
                        .map(PlaylistTrack::getId)
                        .collect(Collectors.toList());
            }

            java.util.Set<Integer> toAdd = new java.util.HashSet<>(serverIds);
            toAdd.removeAll(localIds);

            java.util.Set<Integer> toDelete = new java.util.HashSet<>(localIds);
            toDelete.removeAll(serverIds);

            AppLogger.log("[SYNC] To Add: " + toAdd);
            AppLogger.log("[SYNC] To Delete: " + toDelete);

            // 🔥 FIX: If downloadManager is null (was never created because 0 songs at startup)
            // and new songs are now available, trigger a full playlist reload which will
            // create the DownloadManager, queue downloads, and start playback automatically.
            if (downloadManager == null && !toAdd.isEmpty()) {
                AppLogger.log("[SYNC] DownloadManager is null but " + toAdd.size()
                        + " new tracks detected. Triggering full playlist reload for: " + currentPlaylistName);

                final String reloadPlaylistName = currentPlaylistName;
                Platform.runLater(() -> {
                    try {
                        loadPlaylistAndStart(
                                reloadPlaylistName,
                                globalAlbumHeading,
                                globalTitleLabel,
                                globalProgressSlider,
                                globalLeftTime,
                                globalRightTime,
                                globalControlsWrapper,
                                globalBottomBar,
                                globalDownloadLabel,
                                true
                        );

                        // Update dropdown items in case playlist list changed
                        playlistViewItems.setAll(
                                playlistMaster.stream()
                                        .filter(s -> !s.equals(playlistCurrent[0]))
                                        .collect(Collectors.toList()));

                    } catch (Exception e) {
                        e.printStackTrace();
                        AppLogger.log("[SYNC] Full reload after null downloadManager failed: " + e.getMessage());
                    }
                });
                return; // Skip the rest of sync — loadPlaylistAndStart handles everything
            }

            // ✅ ADD
            for (PlaylistTrack t : serverTracks) {
                if (toAdd.contains(t.getId())) {
                    boolean exists;
                    synchronized (playQueue) {
                        exists = playQueue.stream()
                                .anyMatch(x -> x.getId() == t.getId());
                    }

                    if (!exists) {
                        playQueue.add(t);
                    }

                    if (downloadManager != null) {
                        if (t != null && t.getId() != null && t.getUrl() != null) {
                            downloadManager.registerFallbackUrl(t.getId(), t.getUrl());
                        }
                        downloadManager.queueDownload(t.getId());
                    }
                }
            }

            // ✅ RESHUFFLE remaining queue when new styles/songs are added
            // using the server-provided download sequence order
            if (!toAdd.isEmpty()) {
                synchronized (playQueue) {
                    int startIdx = currentTrackIndex + 1;
                    if (startIdx < playQueue.size()) {
                        List<PlaylistTrack> remaining = new ArrayList<>(
                                playQueue.subList(startIdx, playQueue.size()));
                        reorderTracksBySequence(remaining, currentDownloadSequence);
                        for (int i = 0; i < remaining.size(); i++) {
                            playQueue.set(startIdx + i, remaining.get(i));
                        }
                        AppLogger.log("[SYNC] New styles/songs detected — reordered "
                                + remaining.size() + " remaining tracks in playQueue "
                                + "based on server sequence (from index " + startIdx + ")");
                    }
                }
            }

            // ✅ DELETE
            if (!toDelete.isEmpty() && downloadManager != null) {
                downloadManager.removeFromQueue(toDelete);
            }
            for (Integer id : toDelete) {

                PlaylistTrack current = null;

                if (currentTrackIndex < playQueue.size()) {
                    current = playQueue.get(currentTrackIndex);
                }

                playQueue.removeIf(track -> track.getId() == id);

                deleteSongFile(id);

                if (current != null && current.getId() == id) {
                    Platform.runLater(() -> {
                        try {
                            playNextTrack(null, null, null, null, null, null, null, null);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                }
            }

            // Update Total files count for UI
            try {
                Set<Integer> validIds = new HashSet<>();
                if (currentDownloadSequence != null && !currentDownloadSequence.isEmpty()) {
                    currentGenreTotalFiles = currentDownloadSequence.size();
                    validIds.addAll(currentDownloadSequence);
                } else {
                    currentGenreTotalFiles = serverTracks.size();
                    for (PlaylistTrack t : serverTracks) validIds.add(t.getId());
                }

                int existingInGenre = countExistingSongFiles(validIds);
                currentGenreDownloadedCount.set(existingInGenre);

                recomputeGlobalCountAndUpdateUI();

                if (globalDownloadLabel != null) {
                    Platform.runLater(() -> {
                        updateGenreDownloadLabel(globalDownloadLabel);
                    });
                }
            } catch (Exception e) {
                AppLogger.log("[SYNC] Failed to update download sequence count: " + e.getMessage());
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (apiService != null) {
                apiService.clearCache();
            }
        }
    }

    private void initializeAdSystem() {
        AppLogger.log("[PlayerController] Initializing Ad System");

        // 1. Create AdPlayer with listeners
        adPlayer = new AdPlayer(audioPlayer, new AdPlayer.AdPlaybackListener() {
            @Override
            public void onAdPlaybackStarted(Ad ad, com.musicplayer.scamusica.model.AdAudio adAudio) {

                AppLogger.log("[AdPlayer] Ad started: "
                        + ad.getCampaignName());

                try {
                    com.musicplayer.scamusica.service.LogSyncService.getInstance()
                            .addAdLog(ad.getId(), ad.getCampaignName(), adAudio != null ? adAudio.getAdTitle() : null, adAudio != null ? adAudio.getAudioFile() : null);
                } catch (Exception syncEx) {
                    AppLogger.log("[AdPlayer] Ad log sync failed: " + syncEx.getMessage());
                }

                Platform.runLater(() -> {
                    try {
                        globalTitleLabel.setText("ADVERTISEMENT");
                        globalAlbumHeading.setText(ad.getCampaignName());

                        if (globalProgressSlider != null) {
                            globalProgressSlider.setDisable(true);
                            globalProgressSlider.setMouseTransparent(true);
                        }
                        if (globalControlsWrapper != null) {
                            globalControlsWrapper.setDisable(true);
                        }

                        setGenreSwitchEnabled(false);

                        if (globalBottomBar != null) {
                            Slider volumeSlider = controlsUtil.getVolumeSlider(globalBottomBar);
                            if (volumeSlider != null) {
                                volumeSlider.setValue(currentAdVolume);
                            }
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }

            @Override
            public void onAdPlaybackFinished(Ad ad) {

                AppLogger.log("[AdPlayer] Ad finished: "
                        + ad.getCampaignName());

                Platform.runLater(() -> {
                    try {

                        if (!playQueue.isEmpty() && currentTrackIndex < playQueue.size()) {
                            PlaylistTrack track = playQueue.get(currentTrackIndex);
                            globalTitleLabel.setText(track.getTitle());
                            globalAlbumHeading.setText(currentPlaylistName);
                        }

                        if (globalProgressSlider != null) {
                            globalProgressSlider.setDisable(false);
                            globalProgressSlider.setMouseTransparent(false);
                        }
                        if (globalControlsWrapper != null) {
                            globalControlsWrapper.setDisable(false);
                        }

                        setGenreSwitchEnabled(true);

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }

            @Override
            public void onSongPaused(String reason) {
                AppLogger.log("[AdPlayer] Song paused: " + reason);
            }

            @Override
            public void onSongResumed() {
                AppLogger.log("[AdPlayer] Song resuming after ad");

                Platform.runLater(() -> {
                    try {
                        if (!playQueue.isEmpty() && currentTrackIndex < playQueue.size()) {
                            PlaylistTrack track = playQueue.get(currentTrackIndex);

                            File encryptedFile = new File(SONGS_DIR, "song-" + track.getId() + ".dat");

                            if (encryptedFile.exists()) {
                                AppLogger
                                        .log("[AdPlayer] Resuming from local file: " + encryptedFile.getAbsolutePath());
                                asyncExecutor.submit(() -> {
                                    try {
                                        File tempFile = decryptToTemp(encryptedFile);
                                        synchronized (PlayerController.this) {
                                            if (currentTempFile != null && currentTempFile.exists()) {
                                                currentTempFile.delete();
                                            }
                                            currentTempFile = tempFile;
                                        }
                                        Platform.runLater(() -> {
                                            int originalVol = (int) prefs.getDouble(PREF_VOLUME, 85.0);
                                            long savedTime = adPlayer.getSavedSongTime();
                                            String startTimeOpt = ":start-time=" + (savedTime / 1000.0f);

                                            audioPlayer.setVolume(0);
                                            asyncExecutor.submit(() -> {
                                                try {
                                                    for (int i = 0; i < 50; i++) {
                                                        Platform.runLater(() -> {
                                                            try {
                                                                audioPlayer.setVolume(0);
                                                            } catch (Exception ex) {
                                                            }
                                                        });
                                                        Thread.sleep(30);
                                                    }
                                                } catch (Exception e) {
                                                }
                                            });

                                            audioPlayer.play(tempFile.getAbsolutePath(), savedTime);
                                            audioPlayer.setVolume(0);

                                            schedular.schedule(() -> {
                                                asyncExecutor.submit(() -> {
                                                    try {
                                                        Platform.runLater(() -> {
                                                            try {
                                                                audioPlayer.setVolume(0);
                                                            } catch (Exception ex) {
                                                            }
                                                        });
                                                        int steps = 20;
                                                        for (int i = 1; i <= steps; i++) {
                                                            int currentVol = (int) (originalVol * ((double) i / steps));
                                                            Platform.runLater(() -> {
                                                                try {
                                                                    audioPlayer.setVolume(currentVol);
                                                                } catch (Exception ex) {
                                                                }
                                                            });
                                                            Thread.sleep(100);
                                                        }
                                                        Platform.runLater(() -> {
                                                            try {
                                                                AppLogger.log(
                                                                        "[PlayerController] Song volume restored to: "
                                                                                + originalVol);
                                                                audioPlayer.setVolume(originalVol);
                                                                if (globalBottomBar != null) {
                                                                    Slider volumeSlider = controlsUtil
                                                                            .getVolumeSlider(globalBottomBar);
                                                                    if (volumeSlider != null) {
                                                                        volumeSlider.setValue(originalVol);
                                                                    }
                                                                }
                                                            } catch (Exception ex) {
                                                            }
                                                        });
                                                    } catch (Exception e) {
                                                    }
                                                });
                                            }, 1500, TimeUnit.MILLISECONDS);
                                        });
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                });
                            } else if (NetworkMonitor.getInstance().isOnline()) {
                                AppLogger.log("[AdPlayer] Resuming from URL: " + track.getUrl());
                                int originalVol = (int) prefs.getDouble(PREF_VOLUME, 85.0);
                                long savedTime = adPlayer.getSavedSongTime();
                                String startTimeOpt = ":start-time=" + (savedTime / 1000.0f);

                                audioPlayer.setVolume(0);
                                asyncExecutor.submit(() -> {
                                    try {
                                        for (int i = 0; i < 50; i++) {
                                            Platform.runLater(() -> {
                                                try {
                                                    audioPlayer.setVolume(0);
                                                } catch (Exception ex) {
                                                }
                                            });
                                            Thread.sleep(30);
                                        }
                                    } catch (Exception e) {
                                    }
                                });

                                audioPlayer.play(encodeMediaUrl(track.getUrl()), savedTime);
                                audioPlayer.setVolume(0);

                                schedular.schedule(() -> {
                                    asyncExecutor.submit(() -> {
                                        try {
                                            Platform.runLater(() -> {
                                                try {
                                                    audioPlayer.setVolume(0);
                                                } catch (Exception ex) {
                                                }
                                            });
                                            int steps = 20;
                                            for (int i = 1; i <= steps; i++) {
                                                int currentVol = (int) (originalVol * ((double) i / steps));
                                                Platform.runLater(() -> {
                                                    try {
                                                        audioPlayer.setVolume(currentVol);
                                                    } catch (Exception ex) {
                                                    }
                                                });
                                                Thread.sleep(100);
                                            }
                                            Platform.runLater(() -> {
                                                try {
                                                    AppLogger.log("[PlayerController] Song volume restored to: "
                                                            + originalVol);
                                                    audioPlayer.setVolume(originalVol);
                                                    if (globalBottomBar != null) {
                                                        Slider volumeSlider = controlsUtil
                                                                .getVolumeSlider(globalBottomBar);
                                                        if (volumeSlider != null) {
                                                            volumeSlider.setValue(originalVol);
                                                        }
                                                    }
                                                } catch (Exception ex) {
                                                }
                                            });
                                        } catch (Exception e) {
                                        }
                                    });
                                }, 1500, TimeUnit.MILLISECONDS);
                            } else {
                                AppLogger.log("[AdPlayer] Cannot resume — offline and no local file. Playing next.");
                                playNextTrack(globalAlbumHeading, globalTitleLabel,
                                        globalProgressSlider, null, null,
                                        globalControlsWrapper, globalBottomBar, null);
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }

            @Override
            public void onPlaybackError(Exception ex) {
                AppLogger.log("[AdPlayer] Playback error: " + ex.getMessage());
            }
        });

        adPlayer.setAdVolumeSupplier(() -> currentAdVolume);

        // 2. Fetch ads from server
        try {
            PlaylistApiService api = new PlaylistApiService();
            allAds = api.fetchAds();

            AppLogger.log("[PlayerController] Total ads = " + allAds.size());

            for (Ad ad : allAds) {
                AppLogger.log("==============");
                AppLogger.log("Campaign: " + ad.getCampaignName());
                AppLogger.log("ScheduleType: " + ad.getScheduleType());
                AppLogger.log("PlayTimes: " + ad.getPlayTimes());
                AppLogger.log("ActiveDays: " + ad.getActiveDays());
            }

            if (allAds != null && !allAds.isEmpty()) {
                OfflineCache.saveAdSchedule(allAds);
                AdDownloadManager.downloadAllAds(allAds);
            }

            if (allAds == null) {
                allAds = new ArrayList<>();
            }
            AppLogger.log("[AdSystem] Loaded " + allAds.size() + " ads");
        } catch (Exception e) {
            AppLogger.log("[AdSystem] Failed to load ads online, trying cache...");
            allAds = OfflineCache.loadAdSchedule();
            AppLogger.log("[AdSystem] Loaded " + allAds.size() + " ads from cache");
        }

        // 3. Create scheduler with listeners
        adScheduler = new AdScheduler(allAds, new AdScheduler.AdScheduleListener() {
            @Override
            public void onAdsReady(List<Ad> ads) {
                AppLogger.log("[AdScheduler] " + ads.size() + " ads due to play");
                // Queue them for playback
                if (adPlayer != null) {
                    adPlayer.queueAds(ads);
                }
            }

            @Override
            public void onScheduleError(Exception ex) {
                AppLogger.log("[AdScheduler] Error: " + ex.getMessage());
            }
        });

        // 4. Start the scheduler
        adScheduler.start();
    }

    private void syncAdsFromServer(PlaylistApiService api) throws Exception {

        if (!NetworkMonitor.getInstance().isOnline()) {
            AppLogger.log("[SYNC] Offline — skipping ad sync");
            return;
        }

        List<Ad> serverAds = api.fetchAds();

        if (serverAds != null) {
            allAds = new ArrayList<>(serverAds);
            if (adScheduler != null) {
                adScheduler.updateAds(allAds);
            }
            if (adPlayer != null && !adPlayer.isPlayingAd()) {
                adPlayer.clearQueue();
            }
            OfflineCache.saveAdSchedule(allAds);
            if (!allAds.isEmpty()) {
                AdDownloadManager.downloadAllAds(allAds);
            }
            AdDownloadManager.cleanupOrphanedAdFiles(allAds);
            AppLogger.log("[SYNC] Ads updated: " + allAds.size() + " ads");
        }
    }

    private void deleteSongFile(int id) {
        if (isSongUsedByOtherSequences(id)) {
            AppLogger.log("[DELETE] Song " + id + " still used by other sequences, keeping file");
            return;
        }
        File file = new File(SONGS_DIR, "song-" + id + ".dat");
        if (file.exists()) {
            AppLogger.log("[DELETE] Removing file for ID: " + id);
            file.delete();
        }
    }

    private boolean isSongUsedByOtherSequences(int songId) {
        try {
            for (String title : playlistMaster) {
                if (title.equals(currentPlaylistName)) continue;
                List<PlaylistTrack> tracks = apiService.fetchTracksForGenre(title);
                if (tracks != null) {
                    for (PlaylistTrack t : tracks) {
                        if (t.getId() != null && t.getId() == songId) return true;
                    }
                }
            }
        } catch (Exception e) {
            AppLogger.log("[DELETE] Error checking other sequences: " + e.getMessage());
            return true; // Err on the safe side
        }
        return false;
    }

    private void hideDropdown(VBox dropdownCard) {
        dropdownCard.setVisible(false);
        dropdownCard.setManaged(false);
    }

    private int countExistingSongFiles(Set<Integer> validSongIds) {
        File dir = new File(SONGS_DIR);
        if (!dir.exists() || !dir.isDirectory())
            return 0;
        File[] files = dir.listFiles();
        if (files == null)
            return 0;
        int c = 0;
        for (File f : files) {
            if (!f.isDirectory() && f.getName().startsWith("song-") && f.getName().endsWith(".dat")) {
                if (f.length() > 10_000) {
                    if (validSongIds == null) {
                        c++;
                    } else {
                        try {
                            String idStr = f.getName().substring(5, f.getName().length() - 4);
                            int songId = Integer.parseInt(idStr);
                            if (validSongIds.contains(songId)) c++;
                        } catch (NumberFormatException ignored) {}
                    }
                } else {
                    AppLogger.log("[PlayerController] Deleting incomplete file: " + f.getName() + " (" + f.length() + " bytes)");
                    f.delete();
                }
            }
        }
        return c;
    }

    private void recomputeGlobalCountAndUpdateUI() {
        Platform.runLater(() -> {
            File baseDir = new File(SONGS_DIR);
            if (!baseDir.exists()) {
                boolean created = baseDir.mkdirs();
                AppLogger.log("[PlayerController] Base dir created: " + created);
            }

            // Mac installer permissions fix
            baseDir.setWritable(true, false);
            baseDir.setReadable(true, false);
            baseDir.setExecutable(true, false);

            int globalExisting = countExistingSongFiles(null);
            totalDownloadedCounter.set(globalExisting);
            albumUtil.setSongCount(globalExisting);
        });
    }

    private Button getBigPlayButton(HBox controlsWrapper) {
        try {
            return (Button) ((StackPane) controlsWrapper.getChildren().get(1)).getChildren().get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private void updatePlayButtonState(HBox controlsWrapper) {
        Button bigPlayBtn = getBigPlayButton(controlsWrapper);
        if (bigPlayBtn == null)
            return;

        // We now support streaming fallback, so the play button should always be
        // enabled
        Platform.runLater(() -> bigPlayBtn.setDisable(false));
    }

    private void setGenreSwitchEnabled(boolean enabled) {
        if (playlistPill != null) {
            Platform.runLater(() -> playlistPill.setDisable(!enabled));
        }
        if (playlistDropdownCard != null) {
            Platform.runLater(() -> playlistDropdownCard.setDisable(!enabled));
        }
    }

    private void reorderTracksBySequence(List<PlaylistTrack> tracksToReorder, List<Integer> sequence) {
        if (sequence == null || sequence.isEmpty()) {
            java.util.Collections.shuffle(tracksToReorder);
            return;
        }

        // 1. Group tracks by style and shuffle each bucket
        java.util.Map<String, List<PlaylistTrack>> styleBuckets = new java.util.HashMap<>();
        for (PlaylistTrack t : tracksToReorder) {
            String style = t.getFolderTitle();
            if (style == null || style.isEmpty()) style = "UNKNOWN_STYLE";
            styleBuckets.computeIfAbsent(style, k -> new ArrayList<>()).add(t);
        }
        for (List<PlaylistTrack> bucket : styleBuckets.values()) {
            java.util.Collections.shuffle(bucket);
        }

        // 2. Reconstruct queue based on the style pattern derived from the sequence
        List<PlaylistTrack> reordered = new ArrayList<>();
        java.util.Set<Integer> handledIds = new java.util.HashSet<>();

        for (Integer id : sequence) {
            String style = idToStyleMap.get(id);
            if (style == null || style.isEmpty()) style = "UNKNOWN_STYLE";

            List<PlaylistTrack> bucket = styleBuckets.get(style);
            if (bucket != null && !bucket.isEmpty()) {
                PlaylistTrack pulled = bucket.remove(0); // Pop the first randomized track
                reordered.add(pulled);
                handledIds.add(pulled.getId());
            }
        }

        // 3. Add any leftovers (e.g. if sequence was missing something, or buckets had extra)
        for (PlaylistTrack t : tracksToReorder) {
            if (!handledIds.contains(t.getId())) {
                reordered.add(t);
            }
        }

        tracksToReorder.clear();
        tracksToReorder.addAll(reordered);
    }

    private void loadPlaylistAndStart(String playlistName,
            Label albumHeading,
            Label titleLabel,
            Slider progressSlider,
            Label leftTime,
            Label rightTime,
            HBox controlsWrapper,
            HBox bottomBar,
            Label downloadLabel,
            boolean autoPlay) throws URISyntaxException {

        stopPlayback(progressSlider, leftTime, rightTime, controlsWrapper, downloadLabel);

        playQueue.clear();
        lastServerIds.clear();
        currentTrackIndex = 0;
        isFirstTrackStarted = false;

        try {
            PlaylistApiService playlistApiService = new PlaylistApiService();

            // ✅ STEP 1: Fetch both tracks AND download sequence
            List<PlaylistTrack> fetchedTracks = playlistApiService.fetchTracksForGenre(playlistName);
            List<Integer> downloadSeq = playlistApiService.fetchDownloadSequenceForGenre(playlistName);

            if (downloadSeq == null)
                downloadSeq = new ArrayList<>();

            currentDownloadSequence = new ArrayList<>(downloadSeq);

            // ✅ STEP 2: KEY FIX - Reorder playQueue to match downloadSequence
            // This ensures first songs in queue are the first ones being downloaded
            if (fetchedTracks != null && !fetchedTracks.isEmpty()) {
                for (PlaylistTrack t : fetchedTracks) {
                    idToStyleMap.put(t.getId(), t.getFolderTitle());
                }
                playQueue.addAll(fetchedTracks);
                reorderTracksBySequence(playQueue, currentDownloadSequence);
            }

            recomputeGlobalCountAndUpdateUI();

            if (downloadManager != null) {
                downloadManager.stop();
                downloadManager = null;
            }

            // Set first image AFTER reordering queue
            if (!playQueue.isEmpty() && albumImageView != null) {
                String firstImgUrl = playQueue.get(0).getAlbumImageUrl();
                if (firstImgUrl != null && !firstImgUrl.trim().isEmpty()) {
                    if (!firstImgUrl.equals(currentAlbumImgUrl)) {
                        currentAlbumImgUrl = firstImgUrl;
                        setDefaultAlbumImage();
                        asyncExecutor.submit(() -> {
                            try {
                                Image image = com.musicplayer.scamusica.util.ImageCache.getImage(firstImgUrl);
                                Platform.runLater(() -> albumImageView.setImage(image != null ? image : defaultAlbumImage));
                            } catch (Exception ignored) {
                            }
                        });
                    }
                } else {
                    currentAlbumImgUrl = null;
                    setDefaultAlbumImage();
                }
            }

            currentGenreTotalFiles = downloadSeq.size();

            String genreFolderPath = SONGS_DIR;

            File genreDir = new File(genreFolderPath);
            if (!genreDir.exists()) {
                boolean created = genreDir.mkdirs();
                AppLogger.log("[PlayerController] Genre folder created: " + created + " at " + genreFolderPath);
            }
            genreDir.setWritable(true, false);

            int existingInGenre = countExistingSongFiles(new HashSet<>(downloadSeq));
            currentGenreDownloadedCount.set(existingInGenre);

            updateGenreDownloadLabel(downloadLabel);

            updatePlayButtonState(controlsWrapper);

            boolean needDownload = false;
            if (downloadSeq.isEmpty()) {
                needDownload = false;
            } else {
                for (Integer id : downloadSeq) {
                    File candidate = new File(genreFolderPath, "song-" + id + ".dat");
                    if (candidate.exists() && candidate.length() <= 10_000) {
                        AppLogger.log("[PlayerController] Deleting incomplete file during sequence check: " + candidate.getName() + " (" + candidate.length() + " bytes)");
                        candidate.delete();
                    }
                    if (!candidate.exists() || candidate.length() <= 10_000) {
                        needDownload = true;
                    }
                }
            }

            setGenreSwitchEnabled(true);

            if (!downloadSeq.isEmpty()) {
                final Set<Integer> listenerValidIds = new HashSet<>(downloadSeq);
                downloadManager = new DownloadManager(genreFolderPath,
                        new DownloadManager.DownloadListener() {
                            @Override
                            public void onDownloadStarted(int songId, File outputFile) {
                                currentFileProgressFraction = 0.0;

                                updatePlayButtonState(controlsWrapper);

                                if (downloadLabel != null) {
                                    Platform.runLater(() -> {
                                        try {
                                            downloadLabel.textProperty().unbind();
                                        } catch (Exception ignored) {
                                        }
                                        updateGenreDownloadLabel(downloadLabel);
                                    });
                                }
                            }

                            @Override
                            public void onDownloadProgress(int songId, long bytesDownloaded, long contentLength) {
                                double frac = 0.0;
                                if (contentLength > 0) {
                                    frac = ((double) bytesDownloaded) / ((double) contentLength);
                                    if (frac < 0.0)
                                        frac = 0.0;
                                    if (frac > 1.0)
                                        frac = 1.0;
                                } else {
                                    frac = Math.min(1.0, bytesDownloaded / (1024.0 * 200));
                                }
                                currentFileProgressFraction = frac;
                                updateGenreDownloadLabel(downloadLabel);
                            }

                            // @Override
                            // public void onDownloadCompleted(int songId, File outputFile) {
                            //
                            // recomputeGlobalCountAndUpdateUI();
                            //
                            // Platform.runLater(() -> {
                            //
                            // int newGenreCount = countExistingInGenreFolder(genreFolderPath);
                            // currentGenreDownloadedCount.set(newGenreCount);
                            //
                            // currentFileProgressFraction = 0.0;
                            //
                            // updateGenreDownloadLabel(downloadLabel);
                            // updatePlayButtonState(controlsWrapper);
                            // AppLogger.log("[AUTO-PLAY] Downloaded count: " + newGenreCount);
                            // if (newGenreCount >= 2) {
                            // if (!audioPlayer.isPlaying() && !userPaused) {
                            // try {
                            // AppLogger.log("[AutoPlay] 2 songs downloaded. Starting playback." +
                            // "..");
                            // playTrack(
                            // albumHeading,
                            // titleLabel,
                            // progressSlider,
                            // leftTime,
                            // rightTime,
                            // controlsWrapper,
                            // bottomBar,
                            // downloadLabel,
                            // true
                            // );
                            // } catch (URISyntaxException e) {
                            // e.printStackTrace();
                            // }
                            // }
                            // }
                            // });
                            // }

                            @Override
                            public void onDownloadCompleted(int songId, File outputFile) {
                                recomputeGlobalCountAndUpdateUI();

                                Platform.runLater(() -> {
                                    int newGenreCount = countExistingSongFiles(listenerValidIds);
                                    currentGenreDownloadedCount.set(newGenreCount);
                                    currentFileProgressFraction = 0.0;

                                    updateGenreDownloadLabel(downloadLabel);
                                    updatePlayButtonState(controlsWrapper);
                                    AppLogger.log(
                                            "[AUTO-PLAY] Downloaded: " + newGenreCount + "/" + currentGenreTotalFiles);

                                    // ✅ FIX: Add isFirstTrackStarted check
                                    if (newGenreCount >= 2
                                            && !isFirstTrackStarted // ← ← ← NEW
                                            && !audioPlayer.isPlaying()
                                            && !userPaused
                                            && (playQueue.isEmpty() || currentTrackIndex == 0)) { // ← ← ← NEW

                                        try {
                                            if (playQueue.isEmpty()) {
                                                return;
                                            }

                                            AppLogger.log("[AutoPlay] 2 songs ready. Starting playback...");
                                            playTrack(
                                                    albumHeading,
                                                    titleLabel,
                                                    progressSlider,
                                                    leftTime,
                                                    rightTime,
                                                    controlsWrapper,
                                                    bottomBar,
                                                    downloadLabel,
                                                    true);

                                            isFirstTrackStarted = true; // ← ← ← SET FLAG AFTER PLAY

                                        } catch (URISyntaxException e) {
                                            e.printStackTrace();
                                        }
                                    }
                                });
                            }

                            @Override
                            public void onDownloadSkipped(int songId, File existingFile) {
                                recomputeGlobalCountAndUpdateUI();

                                int newGenreCount = countExistingSongFiles(listenerValidIds);
                                currentGenreDownloadedCount.set(newGenreCount);

                                updateGenreDownloadLabel(downloadLabel);
                                updatePlayButtonState(controlsWrapper);
                            }

                            @Override
                            public void onDownloadFailed(int songId, Exception ex) {
                                AppLogger.log(
                                        "[PlayerController] Download failed id=" + songId + " -> " + ex.getMessage());
                                try {
                                    com.musicplayer.scamusica.service.LogSyncService.getInstance().addErrorLog(
                                            "Download Error", "SongID: " + songId + " - " + ex.getMessage());
                                } catch (Exception logEx) {
                                }
                                currentFileProgressFraction = 0.0;
                                updateGenreDownloadLabel(downloadLabel);
                            }

                            @Override
                            public void onAllDownloadsFinished() {
                                AppLogger.log("[PlayerController] All downloads finished for genre: " + playlistName);
                                setGenreSwitchEnabled(true);

                                recomputeGlobalCountAndUpdateUI();
                                int newGenreCount = countExistingSongFiles(listenerValidIds);
                                currentGenreDownloadedCount.set(newGenreCount);

                                updatePlayButtonState(controlsWrapper);
                            }

                            @Override
                            public void onCancelled() {
                                AppLogger.log("[PlayerController] Downloads cancelled for genre: " + playlistName);
                                setGenreSwitchEnabled(true);
                                recomputeGlobalCountAndUpdateUI();
                                int newGenreCount = countExistingSongFiles(listenerValidIds);
                                currentGenreDownloadedCount.set(newGenreCount);
                                updatePlayButtonState(controlsWrapper);
                            }
                        });

                downloadManager.start();
                synchronized (playQueue) {
                    for (PlaylistTrack pt : playQueue) {
                        if (pt != null && pt.getId() != null && pt.getUrl() != null) {
                            downloadManager.registerFallbackUrl(pt.getId(), pt.getUrl());
                        }
                    }
                }
                for (Integer id : downloadSeq) {
                    downloadManager.queueDownload(id);
                }
            } else {
                currentFileProgressFraction = 0.0;
                updateGenreDownloadLabel(downloadLabel);
                setGenreSwitchEnabled(true);
                updatePlayButtonState(controlsWrapper);
            }

        } catch (Exception e) {
            e.printStackTrace();
            setGenreSwitchEnabled(true);
            updatePlayButtonState(controlsWrapper);
        }

        albumHeading.textProperty().bind(LanguageManager.createStringBinding("label.loading"));
        if (!playQueue.isEmpty()) {
            isFirstTrackStarted = true;

            int savedTrackId = prefs.getInt(PREF_RESUME_TRACK_ID, -1);
            if (savedTrackId != -1) {
                for (int i = 0; i < playQueue.size(); i++) {
                    if (playQueue.get(i).getId() == savedTrackId) {
                        currentTrackIndex = i;
                        AppLogger.log("[PlayerController] Resuming from saved track index: " + currentTrackIndex);
                        break;
                    }
                }
                prefs.remove(PREF_RESUME_TRACK_ID);
            }

            playTrack(
                    albumHeading,
                    titleLabel,
                    progressSlider,
                    leftTime,
                    rightTime,
                    controlsWrapper,
                    bottomBar,
                    downloadLabel,
                    autoPlay);
        } else {
            albumHeading.textProperty().bind(LanguageManager.createStringBinding("label.noSong"));
        }
    }

    private void updateGenreDownloadLabel(Label downloadLabel) {
        if (downloadLabel == null)
            return;

        double percent = 0.0;
        if (currentGenreTotalFiles <= 0) {
            percent = 100.0;
        } else {
            double completed = currentGenreDownloadedCount.get();
            double frac = currentFileProgressFraction;
            percent = ((completed + frac) / (double) currentGenreTotalFiles) * 100.0;
            if (percent < 0.0)
                percent = 0.0;
            if (percent > 100.0)
                percent = 100.0;
        }

        final String text = String.format("%.0f%% %s (%d/%d)", percent, LanguageManager.createStringBinding("label" +
                ".download").get(),
                currentGenreDownloadedCount.get(), currentGenreTotalFiles);

        boolean isDone = (currentGenreDownloadedCount.get() == currentGenreTotalFiles
                && currentGenreTotalFiles > 0);

        Platform.runLater(() -> {
            try {
                downloadLabel.textProperty().unbind();
            } catch (Exception ignored) {
            }
            downloadLabel.setText(text);

            if (isDone) {
                downloadLabel.setStyle("-fx-text-fill: #22c55e;"); // green
            } else {
                downloadLabel.setStyle("-fx-text-fill: #ef4444;"); // red
            }
        });
    }

    private void playTrack(Label albumHeading,
            Label titleLabel,
            Slider progressSlider,
            Label leftTime,
            Label rightTime,
            HBox controlsWrapper,
            HBox bottomBar,
            Label downloadLabel,
            boolean autoPlay) throws URISyntaxException {

        if (playQueue.isEmpty())
            return;

        if (currentTrackIndex < 0 || currentTrackIndex >= playQueue.size()) {
            stopPlayback(progressSlider, leftTime, rightTime, controlsWrapper, downloadLabel);
            return;
        }

        PlaylistTrack track = playQueue.get(currentTrackIndex);
        // New Code for generating the file
        PlaybackHistoryLogger.logSong(track);
        // New Code for generating the file
        AppLogger.log("[PLAYER][PLAY] " + track.getTitle() + " (ID: " + track.getId() + ")");

        // if (albumImageView != null) {
        // String albumImgUrl = track.getAlbumImageUrl();
        // if (albumImgUrl != null && !albumImgUrl.trim().isEmpty()) {
        // try {
        // albumImageView.setImage(new Image(albumImgUrl, true));
        // } catch (Exception ex) {
        // ex.printStackTrace();
        // }
        // }
        // }

        if (albumImageView != null) {
            String albumImgUrl = track.getAlbumImageUrl();
            if (albumImgUrl != null && !albumImgUrl.trim().isEmpty()) {
                if (!albumImgUrl.equals(currentAlbumImgUrl)) {
                    currentAlbumImgUrl = albumImgUrl;
                    setDefaultAlbumImage();
                    asyncExecutor.submit(() -> {
                        try {
                            Image image = com.musicplayer.scamusica.util.ImageCache.getImage(albumImgUrl);
                            Platform.runLater(() -> albumImageView.setImage(image != null ? image : defaultAlbumImage));
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    });
                }
            } else {
                currentAlbumImgUrl = null;
                setDefaultAlbumImage();
            }
        }

        String folderTitle = track.getFolderTitle();
        if (folderTitle != null && !folderTitle.trim().isEmpty()) {
            albumHeading.textProperty().unbind();
            albumHeading.setText(folderTitle);
        } else {
            albumHeading.textProperty().bind(LanguageManager.createStringBinding("label.unknownFolder"));
        }

        titleLabel.setText(track.getTitle());

        if (audioPlayer != null && audioPlayer.isPlaying()) {
            try {
                audioPlayer.pause();
            } catch (Exception ignored) {
            }
        }

        String safeUrl = encodeMediaUrl(track.getUrl());

        if (!safeUrl.contains(".mp3")) {
            safeUrl += "?ext=.mp3";
        }

        AppLogger.log("FIXED MEDIA URL = " + safeUrl);

        try {
            File encryptedFile = new File(SONGS_DIR, "song-" + track.getId() + ".dat");

            AppLogger.log("Encrypted file path: " + encryptedFile.getAbsolutePath());
            AppLogger.log("File exists: " + encryptedFile.exists());

            if (!encryptedFile.exists() && !NetworkMonitor.getInstance().isOnline()) {
                AppLogger.log(
                        "[PLAYER] Offline and file doesn't exist for song-" + track.getId() + ", skipping to next.");
                
                consecutiveErrorCount++;
                if (consecutiveErrorCount > 3) {
                    AppLogger.log("[PLAYER] Too many offline skips. Stopping loop.");
                    return;
                }

                schedular.schedule(() -> {
                    Platform.runLater(() -> {
                        try {
                            playNextTrack(albumHeading, titleLabel, progressSlider,
                                    leftTime, rightTime, controlsWrapper, bottomBar, downloadLabel);
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    });
                }, 2000, java.util.concurrent.TimeUnit.MILLISECONDS);
                return;
            }

            if (encryptedFile.exists() && encryptedFile.length() <= 10_000) {
                AppLogger.log("[PLAYER] Deleting corrupted/incomplete file before playback for song-" + track.getId() + " (" + encryptedFile.length() + " bytes)");
                encryptedFile.delete();
                if (downloadManager != null) {
                    if (track.getUrl() != null) {
                        downloadManager.registerFallbackUrl(track.getId(), track.getUrl());
                    }
                    downloadManager.queueDownload(track.getId());
                }
            }

            if (encryptedFile.exists() && encryptedFile.length() > 10_000) {
                AppLogger.log("[PLAYER] Playing from local file: " + encryptedFile.getAbsolutePath());
                final String fallbackUrl = safeUrl;
                asyncExecutor.submit(() -> {
                    int originalPriority = Thread.currentThread().getPriority();
                    Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
                    try {
                        File tempFile = decryptToTemp(encryptedFile);
                        synchronized (PlayerController.this) {
                            if (currentTempFile != null && currentTempFile.exists()) {
                                currentTempFile.delete();
                            }
                            currentTempFile = tempFile;
                        }
                        String localUrl = tempFile.toURI().toString();

                        if (!localUrl.contains(".mp3")) {
                            localUrl += "?ext=.mp3";
                        }

                        final String finalUrl = localUrl;

                        Platform.runLater(() -> {

                            long savedTime = prefs.getLong(PREF_RESUME_TIME, 0);
                            if (savedTime > 0) {
                                AppLogger.log("[PLAYER] Resuming from saved time: " + savedTime);
                                prefs.remove(PREF_RESUME_TIME);
                                audioPlayer.play(tempFile.getAbsolutePath(), savedTime);
                            } else {
                                audioPlayer.play(tempFile.getAbsolutePath());
                            }

                            if (!vlcHandlersAttached) {

                                attachVlcHandlers(
                                        albumHeading,
                                        titleLabel,
                                        progressSlider,
                                        leftTime,
                                        rightTime,
                                        controlsWrapper,
                                        bottomBar,
                                        downloadLabel,
                                        autoPlay);

                                vlcHandlersAttached = true;
                            }
                        });

                    } catch (Exception e) {
                        AppLogger.log("[PLAYER] Decryption failed for song-" + track.getId()
                                + ", file corrupted. Deleting and streaming from URL. Error: " + e.getMessage());
                        encryptedFile.delete();

                        if (NetworkMonitor.getInstance().isOnline()) {
                            Platform.runLater(() -> {
                                AppLogger.log("[PLAYER] Falling back to stream: " + fallbackUrl);
                                audioPlayer.play(fallbackUrl);
                                if (!vlcHandlersAttached) {
                                    attachVlcHandlers(albumHeading, titleLabel, progressSlider,
                                            leftTime, rightTime, controlsWrapper, bottomBar, downloadLabel, autoPlay);
                                    vlcHandlersAttached = true;
                                }
                            });
                        } else {
                            AppLogger.log("[PLAYER] Offline — cannot stream fallback for song-" + track.getId()
                                    + ", skipping to next.");
                            
                            consecutiveErrorCount++;
                            if (consecutiveErrorCount > 3) {
                                AppLogger.log("[PLAYER] Too many offline decryption skips. Stopping loop.");
                                return;
                            }

                            schedular.schedule(() -> {
                                Platform.runLater(() -> {
                                    try {
                                        playNextTrack(albumHeading, titleLabel, progressSlider,
                                                leftTime, rightTime, controlsWrapper, bottomBar, downloadLabel);
                                    } catch (Exception ex) {
                                        ex.printStackTrace();
                                    }
                                });
                            }, 2000, java.util.concurrent.TimeUnit.MILLISECONDS);
                        }
                    } finally {
                        Thread.currentThread().setPriority(originalPriority);
                    }
                });

                return;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        AppLogger.log("[PLAYER] Streaming from URL: " + safeUrl);

        long savedTime = prefs.getLong(PREF_RESUME_TIME, 0);
        if (savedTime > 0) {
            AppLogger.log("[PLAYER] Resuming from saved time: " + savedTime);
            prefs.remove(PREF_RESUME_TIME);
            audioPlayer.play(safeUrl, savedTime);
        } else {
            audioPlayer.play(safeUrl);
        }

        if (!vlcHandlersAttached) {

            attachVlcHandlers(
                    albumHeading,
                    titleLabel,
                    progressSlider,
                    leftTime,
                    rightTime,
                    controlsWrapper,
                    bottomBar,
                    downloadLabel,
                    autoPlay);

            vlcHandlersAttached = true;
        }
    }

    private void attachVlcHandlers(
            Label albumHeading,
            Label titleLabel,
            Slider progressSlider,
            Label leftTime,
            Label rightTime,
            HBox controlsWrapper,
            HBox bottomBar,
            Label downloadLabel,
            boolean autoPlay) {

        if (currentVlcListener != null) {
            return;
        }

        currentVlcListener = new CvlcAudioPlayer.EventListener() {

            @Override
            public void onPlaying() {
                consecutiveErrorCount = 0;
                Platform.runLater(() -> {
                    FontIcon bigIcon = controlsUtil.getBigPlayIcon(controlsWrapper);
                    if (bigIcon != null) {
                        bigIcon.setIconLiteral("fas-pause");
                    }
                });
            }

            @Override
            public void onPaused() {
                Platform.runLater(() -> {
                    FontIcon bigIcon = controlsUtil.getBigPlayIcon(controlsWrapper);
                    if (bigIcon != null) {
                        bigIcon.setIconLiteral("fas-play");
                    }
                });
            }

            @Override
            public void onStopped() {
                Platform.runLater(() -> {
                    FontIcon bigIcon = controlsUtil.getBigPlayIcon(controlsWrapper);
                    if (bigIcon != null) {
                        bigIcon.setIconLiteral("fas-play");
                    }
                });
            }

            @Override
            public void onTimeChanged(long newTimeMs, long durationMs) {
                Platform.runLater(() -> {
                    if (durationMs > 0) {
                        double progress = (double) newTimeMs / durationMs;
                        progressSlider.setValue(progress * 100);
                        leftTime.setText(formatTime(newTimeMs / 1000));
                        rightTime.setText("-" + formatTime((durationMs - newTimeMs) / 1000));
                    }
                });
            }

            @Override
            public void onError(Exception ex) {
                AppLogger.log("[PLAYER] Error from VLC: " + ex.getMessage() + " (skipped API DB log)");
                
                consecutiveErrorCount++;
                if (consecutiveErrorCount > 3) {
                    AppLogger.log("[PLAYER] Too many streaming errors. Stopping playback to wait for downloads.");
                    return;
                }
                
                schedular.schedule(() -> {
                    Platform.runLater(() -> {
                        try {
                            playNextTrack(albumHeading, titleLabel, progressSlider, leftTime, rightTime, controlsWrapper, bottomBar, downloadLabel);
                        } catch (Exception e) {}
                    });
                }, 2000, java.util.concurrent.TimeUnit.MILLISECONDS);
            }

            @Override
            public void onFinished() {

                if (adPlayer != null && adPlayer.isPlayingAd()) {
                    AppLogger.log("[PLAYER] Media finished but ad is active, ignoring");
                    return;
                }

                synchronized (PlayerController.this) {
                    if (currentTempFile != null) {
                        try {
                            if (currentTempFile.exists())
                                currentTempFile.delete();
                            AppLogger.log("[TEMP] Deleted on finish: " + currentTempFile.getName());
                        } catch (Exception ignored) {
                        }
                        currentTempFile = null;
                    }
                }

                Platform.runLater(() -> {
                    try {

                        AppLogger.log("[PLAYER] Song finished, playing next track");

                        playNextTrack(
                                albumHeading,
                                titleLabel,
                                progressSlider,
                                leftTime,
                                rightTime,
                                controlsWrapper,
                                bottomBar,
                                downloadLabel);

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
        };
        audioPlayer.setEventListener(currentVlcListener);
    }

    private void playNextTrack(Label albumHeading,
            Label titleLabel,
            Slider progressSlider,
            Label leftTime,
            Label rightTime,
            HBox controlsWrapper,
            HBox bottomBar,
            Label downloadLabel) throws URISyntaxException {

        currentTrackIndex++;
        AppLogger.log("[PLAYER] Next track index: " + currentTrackIndex);
        if (currentTrackIndex >= playQueue.size()) {
            AppLogger.log("[PlayerController] All tracks finished. Reordering sequence and looping...");
            reorderTracksBySequence(playQueue, currentDownloadSequence);
            currentTrackIndex = 0;
        }

        playTrack(
                albumHeading,
                titleLabel,
                progressSlider,
                leftTime,
                rightTime,
                controlsWrapper,
                bottomBar,
                downloadLabel,
                true);
    }

    private void setupBigPlayBehaviour(Label albumHeading,
            Label titleLabel,
            HBox controlsWrapper,
            Slider progressSlider,
            Label leftTime,
            Label rightTime,
            HBox bottomBar,
            Label downloadLabel) {

        Button bigPlayBtn;
        StackPane playContainer;
        try {
            playContainer = (StackPane) controlsWrapper.getChildren().get(1);
            bigPlayBtn = (Button) playContainer.getChildren().get(0);
        } catch (Exception e) {
            return;
        }

        FontIcon bigIcon = controlsUtil.getBigPlayIcon(controlsWrapper);

        if (bigPlayBtn == null || bigIcon == null) {
            return;
        }

        javafx.event.EventHandler<javafx.scene.input.MouseEvent> clickHandler = e -> {
            e.consume();
            if (playQueue.isEmpty()) {
                return;
            }

            if (audioPlayer == null || currentTrackIndex >= playQueue.size() || currentTrackIndex < 0) {
                currentTrackIndex = 0;
                try {
                    playTrack(
                            albumHeading,
                            titleLabel,
                            progressSlider,
                            leftTime,
                            rightTime,
                            controlsWrapper,
                            bottomBar,
                            downloadLabel,
                            true);
                } catch (URISyntaxException ex) {
                    throw new RuntimeException(ex);
                }
                return;
            }

            if (audioPlayer.isPlaying()) {
                audioPlayer.pause();
                userPaused = true;
                bigIcon.setIconLiteral("fas-play");
                bigIcon.setIconColor(javafx.scene.paint.Color.WHITE);
            } else {
                if (audioPlayer.getState() == CvlcAudioPlayer.State.STOPPED
                        || audioPlayer.getState() == CvlcAudioPlayer.State.ENDED) {
                    try {
                        playTrack(
                                albumHeading,
                                titleLabel,
                                progressSlider,
                                leftTime,
                                rightTime,
                                controlsWrapper,
                                bottomBar,
                                downloadLabel,
                                true);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                } else {
                    audioPlayer.resume();
                    userPaused = false;
                    bigIcon.setIconLiteral("fas-pause");
                    bigIcon.setIconColor(javafx.scene.paint.Color.WHITE);
                }
            }
        };

        bigPlayBtn.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, clickHandler);
        playContainer.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, clickHandler);
    }

    private void stopPlayback(Slider progressSlider,
            Label leftTime,
            Label rightTime,
            HBox controlsWrapper,
            Label downloadLabel) {

        // Intentionally NOT removing VLC listener or setting vlcHandlersAttached to
        // false
        // to prevent JNA native callback memory leaks

        synchronized (PlayerController.this) {
            if (currentTempFile != null) {
                try {
                    if (currentTempFile.exists())
                        currentTempFile.delete();
                } catch (Exception ignored) {
                }
                currentTempFile = null;
            }
        }

        if (audioPlayer != null) {
            try {
                audioPlayer.stop();
            } catch (Exception ignored) {
            }
        }

        if (leftTime != null) {
            try {
                leftTime.textProperty().unbind();
            } catch (Exception ignored) {
            }
        }

        if (rightTime != null) {
            try {
                rightTime.textProperty().unbind();
            } catch (Exception ignored) {
            }
        }

        if (downloadLabel != null) {
            try {
                downloadLabel.textProperty().unbind();
            } catch (Exception ignored) {
            }
        }

        if (progressSlider != null) {
            try {
                progressSlider.valueProperty().unbind();
            } catch (Exception ignored) {
            }
            progressSlider.setValue(0);
        }

        if (leftTime != null) {
            leftTime.setText("0:00");
        }

        if (rightTime != null) {
            rightTime.setText("-0:00");
        }

        if (downloadLabel != null) {
            downloadLabel.textProperty().bind(Bindings.concat(
                    "0% ",
                    LanguageManager.createStringBinding("label.download")));
        }

        if (downloadManager != null) {
            downloadManager.stop();
            downloadManager = null;
        }

        FontIcon bigIcon = controlsUtil.getBigPlayIcon(controlsWrapper);
        if (bigIcon != null) {
            bigIcon.setIconLiteral("fas-play");
            bigIcon.setIconColor(javafx.scene.paint.Color.WHITE);
        }
    }

    private String encodeMediaUrl(String rawUrl) {
        try {
            URL url = new URL(rawUrl);
            String normalizedPath = Normalizer.normalize(url.getPath(), Normalizer.Form.NFC);

            String encodedPath = Arrays.stream(normalizedPath.split("/"))
                    .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8)
                            .replace("+", "%20"))
                    .collect(Collectors.joining("/"));

            String protocol = url.getProtocol();
            String host = url.getHost();
            int port = url.getPort();
            String portStr = (port == -1) ? "" : ":" + port;

            return protocol + "://" + host + portStr + encodedPath;

        } catch (Exception e) {
            System.err.println("URL Encoding Error: " + e.getMessage());
            return rawUrl;
        }
    }

    private File decryptToTemp(File encryptedFile) throws Exception {
        File tempDir = new File(System.getProperty("user.home")
                + File.separator + ".scamusica"
                + File.separator + "temp");
        tempDir.mkdirs();
        File tempFile = new File(tempDir, "play_" + System.currentTimeMillis() + ".mp3");

        try (FileInputStream fis = new FileInputStream(encryptedFile);
                CipherInputStream cis = CryptoUtil.decrypt(fis);
                FileOutputStream fos = new FileOutputStream(tempFile)) {

            byte[] buffer = new byte[8192];
            int read;

            while ((read = cis.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
        } catch (Exception e) {
            try {
                com.musicplayer.scamusica.service.LogSyncService.getInstance().addErrorLog(
                        "Decryption Error", "File: " + encryptedFile.getName() + " - " + e.getMessage());
            } catch (Exception logEx) {
            }
            throw e;
        }

        return tempFile;
    }

    private String formatTime(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        return String.format("%d:%02d", m, s);
    }

    public static void main(String[] args) {
        launch(args);
    }
    private void cleanupOrphanedSongs(List<String> serverTitles) {
        try {
            Set<Integer> allValidIds = new HashSet<>();
            for (String title : serverTitles) {
                try {
                    List<Integer> seq = apiService.fetchDownloadSequenceForGenre(title);
                    if (seq != null) allValidIds.addAll(seq);
                } catch (Exception e) {
                    AppLogger.log("[CLEANUP] Failed to fetch sequence for: " + title);
                }
            }

            File songsDir = new File(SONGS_DIR);
            if (songsDir.exists() && songsDir.isDirectory()) {
                File[] files = songsDir.listFiles();
                if (files != null) {
                    int deletedCount = 0;
                    for (File f : files) {
                        if (f.getName().startsWith("song-") && f.getName().endsWith(".dat")) {
                            try {
                                int id = Integer.parseInt(f.getName().substring(5, f.getName().length() - 4));
                                if (!allValidIds.contains(id)) {
                                    if (f.delete()) {
                                        deletedCount++;
                                        AppLogger.log("[CLEANUP] Removed orphaned song: " + f.getName());
                                    }
                                }
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                    if (deletedCount > 0) {
                        AppLogger.log("[CLEANUP] Total orphaned songs deleted: " + deletedCount);
                    }
                }
            }

            // Clean up cache for sequences no longer in serverTitles
            List<String> cachedTitles = com.musicplayer.scamusica.util.OfflineCache.loadPlaylistTitles();
            for (String cachedTitle : cachedTitles) {
                if (!serverTitles.contains(cachedTitle)) {
                    AppLogger.log("[CLEANUP] Removing cache for unassigned sequence: " + cachedTitle);
                    com.musicplayer.scamusica.util.OfflineCache.removeSequenceCache(cachedTitle);
                }
            }

        } catch (Exception e) {
            AppLogger.log("[CLEANUP] Error during orphaned songs cleanup: " + e.getMessage());
        }
    }

    private void shutdownApp() {
        AppLogger.log("[APP] Closing application");
        AppLogger.close();

        running = false;

        if (queueWorkerThread != null) {
            queueWorkerThread.interrupt();
        }

        if (schedular != null) {
            schedular.shutdownNow();
        }

        if (audioPlayer != null) {
            try {
                audioPlayer.stop();
            } catch (Exception ignored) {
            }
        }

        if (downloadManager != null) {
            try {
                downloadManager.stop();
            } catch (Exception ignored) {
            }
        }

        if (adScheduler != null) {
            adScheduler.stop();
        }
        if (adPlayer != null) {
            adPlayer.stop();
        }

        NetworkMonitor.getInstance().stop();
        MemoryWatchdog.getInstance().stop();
        com.musicplayer.scamusica.service.HeartbeatService.getInstance().stop();
        com.musicplayer.scamusica.service.LogSyncService.getInstance().stop();
    }
}