package com.musicplayer.scamusica.service;

import com.musicplayer.scamusica.util.AppLogger;
import javafx.application.Platform;

import java.io.*;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class CvlcAudioPlayer {
    public interface EventListener {
        void onPlaying();
        void onPaused();
        void onStopped();
        void onFinished();
        void onTimeChanged(long newTimeMs, long durationMs);
        void onError(Exception ex);
    }

    private Process vlcProcess;
    private BufferedWriter writer;
    private BufferedReader reader;
    private EventListener listener;
    private ScheduledExecutorService poller;
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CvlcCommand-Thread");
        t.setDaemon(true);
        return t;
    });

    private volatile long currentDurationMs = -1;
    private volatile long currentTimeMs = 0;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private volatile int currentVolume = 256; // 256 is 100% in VLC RC
    private volatile String currentState = "stopped";
    private volatile String pendingCommand = null;
    private volatile boolean isUserPaused = false;
    private Thread readerThread;
    
    private final AtomicInteger playbackId = new AtomicInteger(0);

    public CvlcAudioPlayer() {
    }

    public void setEventListener(EventListener listener) {
        this.listener = listener;
    }

    public void play(String mediaUrl) {
        play(mediaUrl, 0);
    }

    public void playAndWait(String mediaUrl) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        EventListener tempListener = this.listener;
        
        final int[] expectedIdHolder = new int[1];

        this.listener = new EventListener() {
            private boolean isValid() {
                int expectedId = expectedIdHolder[0];
                if (expectedId == 0) return true;
                if (playbackId.get() != expectedId) {
                    latch.countDown();
                    return false;
                }
                return true;
            }

            @Override public void onPlaying() { 
                if (!isValid()) return;
                if (tempListener != null) tempListener.onPlaying(); 
            }
            @Override public void onPaused() { 
                if (!isValid()) return;
                if (tempListener != null) tempListener.onPaused(); 
            }
            @Override public void onStopped() { 
                if (!isValid()) return;
                if (tempListener != null) tempListener.onStopped(); 
            }
            @Override public void onFinished() {
                if (isValid() && tempListener != null) {
                    tempListener.onFinished();
                }
                latch.countDown();
            }
            @Override public void onTimeChanged(long newTimeMs, long durationMs) {
                if (!isValid()) return;
                if (tempListener != null) tempListener.onTimeChanged(newTimeMs, durationMs);
            }
            @Override public void onError(Exception ex) {
                if (isValid() && tempListener != null) {
                    tempListener.onError(ex);
                }
                latch.countDown();
            }
        };

        play(mediaUrl, 0);
        expectedIdHolder[0] = playbackId.get();
        boolean completed = false;
        try {
            completed = latch.await(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            AppLogger.log("[CvlcAudioPlayer] playAndWait interrupted");
            throw e;
        }
        if (!completed) {
            AppLogger.log("[CvlcAudioPlayer] playAndWait timed out after 10 minutes, force stopping");
            stop();
        }
        this.listener = tempListener;
    }

    public void play(String mediaUrl, long startTimeMs) {
        playbackId.incrementAndGet();
        stop();
        isRunning.set(true);
        currentDurationMs = -1;
        currentTimeMs = 0;
        currentState = "stopped";
        isUserPaused = false;
        pendingCommand = null;

        commandExecutor.submit(() -> {
            try {
                String os = System.getProperty("os.name").toLowerCase();
                String vlcCmd = os.contains("win") ? "vlc" : (os.contains("mac") ? "/Applications/VLC.app/Contents/MacOS/VLC" : "cvlc");

                ProcessBuilder pb = new ProcessBuilder(vlcCmd, "--no-video", "-I", "rc", "--rc-fake-tty", "--play-and-exit");
                if (startTimeMs > 0) {
                    pb.command().add("--start-time=" + (startTimeMs / 1000.0f));
                }
                pb.command().add(mediaUrl);

                pb.redirectErrorStream(true);
                vlcProcess = pb.start();

                writer = new BufferedWriter(new OutputStreamWriter(vlcProcess.getOutputStream()));
                reader = new BufferedReader(new InputStreamReader(vlcProcess.getInputStream()));

                readerThread = new Thread(() -> {
                    try {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            handleVlcOutput(line);
                        }
                    } catch (IOException e) {
                        if (isRunning.get()) {
                            AppLogger.log("[CvlcAudioPlayer] Reader error: " + e.getMessage());
                        }
                    } finally {
                        if (isRunning.get()) {
                            AppLogger.log("[CvlcAudioPlayer] Process exited normally");
                            currentState = "stopped";
                            isRunning.set(false);
                            if (listener != null) {
                                Platform.runLater(() -> listener.onFinished());
                            }
                        }
                    }
                }, "VlcReader-Thread");
                readerThread.setDaemon(true);
                readerThread.start();

                setVolumeInternal(currentVolume);

                poller = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "VlcPoller-Thread");
                    t.setDaemon(true);
                    return t;
                });

                poller.scheduleAtFixedRate(() -> {
                    try {
                        if (!isRunning.get()) return;
                        
                        // Always query status to detect play/pause state
                        sendCommand("status");
                        
                        if (!"playing".equals(currentState) && !"paused".equals(currentState)) {
                            pendingCommand = null;
                            return;
                        }
                        
                        // We only query length until we have it
                        if (currentDurationMs <= 0) {
                            sendCommand("get_length");
                        } else {
                            sendCommand("get_time");
                        }
                    } catch (Exception e) {
                        AppLogger.log("[CvlcAudioPlayer] Poller error: " + e.getMessage());
                    }
                }, 500, 500, TimeUnit.MILLISECONDS);

            } catch (Exception e) {
                AppLogger.log("[CvlcAudioPlayer] Failed to start VLC: " + e.getMessage());
                isRunning.set(false);
                if (listener != null) {
                    Platform.runLater(() -> listener.onError(e));
                }
            }
        });
    }

    private void handleVlcOutput(String line) {
        line = line.trim();
        if (line.isEmpty() || line.equals(">")) return;

        if (line.contains("state playing")) {
            if (!"playing".equals(currentState)) {
                currentState = "playing";
                if (listener != null) Platform.runLater(() -> listener.onPlaying());
            }
        } else if (line.contains("state paused")) {
            if (!"paused".equals(currentState)) {
                currentState = "paused";
                if (listener != null) Platform.runLater(() -> listener.onPaused());
            }
        } else if (line.contains("state stopped")) {
            if (!"stopped".equals(currentState)) {
                currentState = "stopped";
                if (listener != null) Platform.runLater(() -> listener.onStopped());
            }
        } else {
            try {
                String numStr = line;
                if (numStr.startsWith(">")) numStr = numStr.substring(1).trim();
                long val = Long.parseLong(numStr);

                String expected = pendingCommand;
                pendingCommand = null; // Clear immediately after parsing a number

                if ("get_length".equals(expected)) {
                    if (val > 0) {
                        currentDurationMs = val * 1000;
                    }
                } else if ("get_time".equals(expected)) {
                    currentTimeMs = val * 1000;
                    if (listener != null && currentDurationMs > 0) {
                        long t = currentTimeMs;
                        long d = currentDurationMs;
                        Platform.runLater(() -> listener.onTimeChanged(t, d));
                    }
                }
            } catch (NumberFormatException ignored) {}
        }
    }

    private void sendCommand(String cmd) {
        commandExecutor.submit(() -> {
            if (writer == null || !isRunning.get()) return;
            try {
                if (cmd.equals("get_time") || cmd.equals("get_length")) {
                    int waits = 0;
                    while (pendingCommand != null && waits < 10) {
                        try { Thread.sleep(10); } catch (Exception ignored) {}
                        waits++;
                    }
                    pendingCommand = cmd;
                }
                writer.write(cmd + "\n");
                writer.flush();
            } catch (IOException ignored) {}
        });
    }

    public void pause() {
        if (!isUserPaused && "playing".equals(currentState)) {
            isUserPaused = true;
            sendCommand("pause");
        }
    }

    public void resume() {
        if (isUserPaused || "paused".equals(currentState)) {
            isUserPaused = false;
            sendCommand("pause"); // VLC RC 'pause' toggles play/pause
        }
    }

    public void stop() {
        isRunning.set(false);
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        if (poller != null) {
            poller.shutdownNow();
            poller = null;
        }
        if (vlcProcess != null) {
            try {
                sendCommand("quit");
                try { Thread.sleep(300); } catch (Exception ignored) {}
                vlcProcess.destroy();
                if (vlcProcess.isAlive()) {
                    vlcProcess.destroyForcibly();
                }
                try { vlcProcess.waitFor(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
            } catch (Exception ignored) {}
            vlcProcess = null;
        }
        if (writer != null) {
            try { writer.close(); } catch (Exception ignored) {}
            writer = null;
        }
        if (reader != null) {
            try { reader.close(); } catch (Exception ignored) {}
            reader = null;
        }
        currentState = "stopped";
    }

    public void setVolume(int percentage) {
        // VLC RC volume is 0-256 for 0-100%, but actually it's 0-512 sometimes.
        // Usually, 256 is 100%. Let's scale percentage (0-100) to 0-256.
        int vlcVol = (int) ((percentage / 100.0) * 256.0);
        this.currentVolume = vlcVol;
        setVolumeInternal(vlcVol);
    }

    public int getVolume() {
        // scale 0-256 back to 0-100
        return (int) ((currentVolume / 256.0) * 100.0);
    }

    private void setVolumeInternal(int vlcVol) {
        sendCommand("volume " + vlcVol);
    }

    public void seek(float percentage) {
        if (currentDurationMs > 0) {
            long targetTimeSec = (long) ((percentage) * (currentDurationMs / 1000.0));
            sendCommand("seek " + targetTimeSec);
        }
    }

    public boolean isPlaying() {
        return "playing".equals(currentState);
    }

    public long getTime() {
        return currentTimeMs;
    }

    public long getLength() {
        return currentDurationMs;
    }

    public State getState() {
        switch (currentState) {
            case "playing": return State.PLAYING;
            case "paused": return State.PAUSED;
            case "stopped": return State.STOPPED;
            default: return State.STOPPED;
        }
    }

    public enum State {
        PLAYING, PAUSED, STOPPED, ENDED
    }
}
