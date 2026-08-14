import re

with open("src/main/java/com/musicplayer/scamusica/controller/PlayerController.java", "r") as f:
    content = f.read()

# Find the start of syncWithServer
start_idx = content.find("private void syncWithServer() {")
if start_idx == -1:
    print("Could not find syncWithServer")
    exit(1)

# Find the end of syncWithServer by counting braces
brace_count = 0
end_idx = -1
for i in range(start_idx, len(content)):
    if content[i] == '{':
        brace_count += 1
    elif content[i] == '}':
        brace_count -= 1
        if brace_count == 0:
            end_idx = i + 1
            break

original_method = content[start_idx:end_idx]

new_method = """private void syncWithServer() {
        if (!running)
            return;

        PlaylistApiService apiService = null;
        try {
            apiService = new PlaylistApiService();

            if (!NetworkMonitor.getInstance().isOnline())
                return;

            String currentPlaylist = currentPlaylistName;
            if (currentPlaylist == null)
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

                if (fetchedTitles == null || fetchedTitles.isEmpty()) {
                    serverTitles = new ArrayList<>(java.util.Arrays.asList("Default"));
                    AppLogger.log("[SYNC] API returned empty titles, falling back to Default sequence.");
                } else {
                    serverTitles = fetchedTitles;
                }

                if (serverTitles != null && !serverTitles.isEmpty()) {
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
                            cleanupOrphanedSequences(titlesForCleanup);
                        } catch (Exception e) {
                            AppLogger.log("[SYNC] Orphaned sequence cleanup failed: " + e.getMessage());
                        }
                    });

                    // Execute the auto-switch
                    if (sequenceSwitched && nextSequence != null) {
                        currentPlaylistName = nextSequence;
                        playlistCurrent[0] = nextSequence;
                        final String finalNextSeq = nextSequence;

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
                        downloadManager.queueDownload(t.getId());
                    }
                }
            }

            // ✅ DELETE
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
                List<Integer> serverDownloadSeq = apiService.fetchDownloadSequenceForGenre(currentPlaylistName);
                if (serverDownloadSeq != null) {
                    currentGenreTotalFiles = serverDownloadSeq.size();
                } else {
                    currentGenreTotalFiles = serverTracks.size();
                }

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
    }"""

content = content.replace(original_method, new_method)

with open("src/main/java/com/musicplayer/scamusica/controller/PlayerController.java", "w") as f:
    f.write(content)

print("Patch applied successfully.")
