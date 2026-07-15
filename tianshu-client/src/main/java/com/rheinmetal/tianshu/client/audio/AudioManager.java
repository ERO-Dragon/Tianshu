package com.rheinmetal.tianshu.client.audio;

import com.rheinmetal.tianshu.api.IAudioBridge;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class AudioManager implements IAudioBridge {

    private static final System.Logger LOGGER = System.getLogger(AudioManager.class.getName());

    private TargetDataLine targetDataLine;
    private SourceDataLine sourceDataLine;
    private SourceDataLine ttsDataLine;
    private final AtomicInteger ttsPlaybackTurnId = new AtomicInteger(0);

    private final AtomicBoolean isHardwareRunning = new AtomicBoolean(false);
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);

    private ByteArrayOutputStream audioBuffer;
    private Consumer<byte[]> streamChunkConsumer;
    private final Object streamBufferLock = new Object();
    private final ByteArrayOutputStream streamTempBuffer = new ByteArrayOutputStream();
    private int streamChunkCounter = 0;

    private final ExecutorService executorService = new ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8),
            task -> {
                Thread thread = new Thread(task, "Tianshu-Audio");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

    private volatile Mixer.Info currentMicMixer = null;
    private volatile int currentMicIndex = -1;
    private volatile Runnable onPlaybackFinished;
    private final AtomicBoolean micTransition = new AtomicBoolean(false);
    private volatile String pendingMicName = null;

    private Mixer.Info findRealPhysicalMic(DataLine.Info info) {
        Mixer.Info fallbackMic = null;
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            String name = mixerInfo.getName().toLowerCase();
            String desc = mixerInfo.getDescription().toLowerCase();

            if (name.contains("主声音捕获") ||
                    name.contains("软件") || name.contains("software") || name.contains("回环") || name.contains("loopback")
                    || name.contains("立体声混音") || name.contains("stereo mix") || name.contains("虚拟音频")
                    || name.contains("virtual audio cable") || name.contains("wave out")) {
                continue;
            }

            try {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                if (mixer.isLineSupported(info)) {
                    if (desc.contains("high definition audio") || desc.contains("usb audio") ||
                            desc.contains("realtek") || name.contains("usb") || desc.contains("logitech") ||
                            desc.contains("razer") || desc.contains("hyperx") || desc.contains("steelseries")) {
                        LOGGER.log(System.Logger.Level.INFO, "Selected physical microphone: " + mixerInfo.getName());
                        return mixerInfo;
                    }
                    if (fallbackMic == null)
                        fallbackMic = mixerInfo;
                }
            } catch (Exception e) {
            }
        }
        if (fallbackMic != null)
            LOGGER.log(System.Logger.Level.WARNING, "Using fallback microphone: " + fallbackMic.getName());
        return fallbackMic;
    }

    @Override
    public void ensureHardwareRunning() {
        if (!isHardwareRunning.compareAndSet(false, true))
            return;

        executeAudioTask(() -> {
            TargetDataLine captureLine = null;
            try {
                AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                Mixer.Info bestMixer = currentMicMixer != null ? currentMicMixer : findRealPhysicalMic(info);

                if (bestMixer != null) {
                    captureLine = (TargetDataLine) AudioSystem.getMixer(bestMixer).getLine(info);
                    targetDataLine = captureLine;
                    LOGGER.log(System.Logger.Level.INFO, "Opened persistent microphone: " + bestMixer.getName());
                }
                if (captureLine == null) {
                    LOGGER.log(System.Logger.Level.ERROR, "No usable microphone was found");
                    isHardwareRunning.set(false);
                    return;
                }

                captureLine.open(format);
                captureLine.start();
                LOGGER.log(System.Logger.Level.INFO, "Persistent microphone worker started");

                byte[] rawBuffer = new byte[1600];
                while (isHardwareRunning.get()) {
                    int bytesRead = captureLine.read(rawBuffer, 0, rawBuffer.length);
                    if (bytesRead <= 0)
                        continue;

                    Consumer<byte[]> chunkConsumer = streamChunkConsumer;
                    if (isStreaming.get() && chunkConsumer != null) {
                        byte[] chunk = appendStreamChunk(rawBuffer, bytesRead);
                        if (chunk.length > 0) {
                            chunkConsumer.accept(chunk);
                        }
                    } else if (isRecording.get()) {
                        audioBuffer.write(rawBuffer, 0, bytesRead);
                    } else {
                        resetStreamBuffer();
                    }
                }
            } catch (Exception e) {
                LOGGER.log(System.Logger.Level.ERROR, "Persistent microphone worker failed", e);
            } finally {
                if (captureLine != null) {
                    try {
                        captureLine.stop();
                    } catch (Exception ignored) {
                    }
                    try {
                        captureLine.close();
                    } catch (Exception ignored) {
                    }
                }
                if (targetDataLine == captureLine) {
                    targetDataLine = null;
                }
                isHardwareRunning.set(false);
                isRecording.set(false);
                isStreaming.set(false);
                LOGGER.log(System.Logger.Level.INFO, "Persistent microphone worker stopped");
            }
        }, () -> isHardwareRunning.set(false));
    }

    @Override
    public void startRecording() {
        if (isRecording.get() || isStreaming.get())
            return;
        ensureHardwareRunning();
        audioBuffer = new ByteArrayOutputStream();
        isRecording.set(true);
        LOGGER.log(System.Logger.Level.INFO, "PTT recording started");
    }

    @Override
    public byte[] stopRecording() {
        isRecording.set(false);
        try {
            Thread.sleep(60);
        } catch (InterruptedException e) {
        }
        if (audioBuffer != null) {
            byte[] data = audioBuffer.toByteArray();
            LOGGER.log(System.Logger.Level.INFO, "PTT recording completed, bytes=" + data.length);
            return data;
        }
        return new byte[0];
    }

    @Override
    public void startStreamRecording(Consumer<byte[]> onAudioChunk) {
        if (isStreaming.get() || isRecording.get())
            return;
        this.streamChunkConsumer = onAudioChunk;
        ensureHardwareRunning();
        isStreaming.set(true);
        LOGGER.log(System.Logger.Level.INFO, "Streaming capture started");
    }

    @Override
    public void stopStreamRecording() {
        isStreaming.set(false);
        Consumer<byte[]> consumer = streamChunkConsumer;
        byte[] tail = drainStreamBuffer();
        this.streamChunkConsumer = null;
        if (consumer != null && tail.length > 0) {
            consumer.accept(tail);
        }
        LOGGER.log(System.Logger.Level.INFO, "Streaming capture stopped");
    }

    private byte[] appendStreamChunk(byte[] rawBuffer, int bytesRead) {
        synchronized (streamBufferLock) {
            streamTempBuffer.write(rawBuffer, 0, bytesRead);
            streamChunkCounter += bytesRead;
            if (streamChunkCounter < 3200) {
                return new byte[0];
            }
            return drainStreamBufferLocked();
        }
    }

    private byte[] drainStreamBuffer() {
        synchronized (streamBufferLock) {
            return drainStreamBufferLocked();
        }
    }

    private byte[] drainStreamBufferLocked() {
        if (streamTempBuffer.size() == 0) {
            streamChunkCounter = 0;
            return new byte[0];
        }
        byte[] chunk = streamTempBuffer.toByteArray();
        streamTempBuffer.reset();
        streamChunkCounter = 0;
        return chunk;
    }

    private void resetStreamBuffer() {
        synchronized (streamBufferLock) {
            streamTempBuffer.reset();
            streamChunkCounter = 0;
        }
    }

    public synchronized void playAudio(byte[] audioData) {
        playAudio(audioData, 16000);
    }

    public synchronized void playAudio(byte[] audioData, int sampleRate) {
        if (audioData == null || audioData.length == 0)
            return;
        executeAudioTask(() -> {
            try {
                AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                if (!AudioSystem.isLineSupported(info))
                    return;
                sourceDataLine = (SourceDataLine) AudioSystem.getLine(info);
                sourceDataLine.open(format);
                sourceDataLine.start();
                isPlaying.set(true);
                sourceDataLine.write(audioData, 0, audioData.length);
                sourceDataLine.drain();
            } catch (LineUnavailableException e) {
                LOGGER.log(System.Logger.Level.ERROR, "Unable to open playback device", e);
            } catch (Exception e) {
                LOGGER.log(System.Logger.Level.ERROR, "Audio playback failed", e);
            } finally {
                if (sourceDataLine != null) {
                    sourceDataLine.stop();
                    sourceDataLine.close();
                    sourceDataLine = null;
                }
                isPlaying.set(false);
            }
        }, () -> { });
    }

    @Override
    public void startTtsPlayback(int sampleRate) {
        stopTtsPlayback();
        ttsPlaybackTurnId.incrementAndGet();
        try {
            AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(info))
                return;
            ttsDataLine = (SourceDataLine) AudioSystem.getLine(info);
            ttsDataLine.open(format);
            ttsDataLine.start();
            LOGGER.log(System.Logger.Level.INFO, "TTS playback channel opened, sampleRate=" + sampleRate);
        } catch (LineUnavailableException e) {
            LOGGER.log(System.Logger.Level.ERROR, "Unable to open TTS playback channel", e);
        }
    }

    @Override
    public void feedTtsAudio(byte[] audioData) {
        SourceDataLine line = ttsDataLine;
        if (line == null || !line.isOpen()) return;
        if (audioData == null || audioData.length == 0) return;
        try {
            line.write(audioData, 0, audioData.length);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void finishTtsPlayback() {
        SourceDataLine line = ttsDataLine;
        if (line != null) {
            try {
                line.drain();
            } catch (Exception e) {
                LOGGER.log(System.Logger.Level.ERROR, "TTS playback drain failed", e);
            }
        }
        stopTtsPlayback();
        Runnable cb = onPlaybackFinished;
        onPlaybackFinished = null;
        if (cb != null) {
            cb.run();
        }
    }

    @Override
    public void setOnPlaybackFinished(Runnable callback) {
        this.onPlaybackFinished = callback;
    }

    @Override
    public void stopTtsPlayback() {
        onPlaybackFinished = null;
        SourceDataLine line = ttsDataLine;
        ttsDataLine = null;
        if (line != null) {
            try {
                line.stop();
                line.close();
            } catch (Exception e) {
                LOGGER.log(System.Logger.Level.ERROR, "TTS playback channel close failed", e);
            }
        }
    }

    @Override
    public void stopPlayback() {
        isPlaying.set(false);
    }

    @Override
    public boolean isRecording() {
        return isRecording.get();
    }

    @Override
    public boolean isPlaying() {
        return isPlaying.get();
    }

    @Override
    public boolean isStreaming() {
        return isStreaming.get();
    }

    public List<String> getAvailableMicNames() {
        List<String> names = new ArrayList<>();
        AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            String name = mixerInfo.getName().toLowerCase();
            if (name.contains("主声音捕获") || name.contains("软件") || name.contains("software")
                    || name.contains("回环") || name.contains("loopback") || name.contains("立体声混音")
                    || name.contains("stereo mix") || name.contains("虚拟音频") || name.contains("virtual audio cable")
                    || name.contains("wave out")) {
                continue;
            }
            try {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                if (mixer.isLineSupported(info)) {
                    names.add(mixerInfo.getName());
                }
            } catch (Exception ignored) {
            }
        }
        return names;
    }

    public List<Mixer.Info> getAvailableMicMixers() {
        List<Mixer.Info> mixers = new ArrayList<>();
        AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            String name = mixerInfo.getName().toLowerCase();
            if (name.contains("主声音捕获") || name.contains("软件") || name.contains("software")
                    || name.contains("回环") || name.contains("loopback") || name.contains("立体声混音")
                    || name.contains("stereo mix") || name.contains("虚拟音频") || name.contains("virtual audio cable")
                    || name.contains("wave out")) {
                continue;
            }
            try {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                if (mixer.isLineSupported(info)) {
                    mixers.add(mixerInfo);
                }
            } catch (Exception ignored) {
            }
        }
        return mixers;
    }

    public int getCurrentMicIndex() {
        return currentMicIndex;
    }

    public String getCurrentMicName() {
        if (currentMicMixer != null) return currentMicMixer.getName();
        List<String> names = getAvailableMicNames();
        return names.isEmpty() ? "" : names.get(0);
    }

    @Override
    public void selectMic(String micName) {
        pendingMicName = micName == null ? "" : micName;
        scheduleMicTransition();
    }

    private void scheduleMicTransition() {
        if (!micTransition.compareAndSet(false, true)) {
            return;
        }
        executeAudioTask(() -> {
            try {
                while (true) {
                    String requested = pendingMicName;
                    pendingMicName = null;
                    applyMicSelection(requested);
                    if (pendingMicName == null) {
                        return;
                    }
                }
            } finally {
                micTransition.set(false);
                if (pendingMicName != null) {
                    scheduleMicTransition();
                }
            }
        }, () -> micTransition.set(false));
    }

    private void applyMicSelection(String micName) {
        List<Mixer.Info> mixers = getAvailableMicMixers();
        Mixer.Info selected = null;
        int selectedIndex = -1;
        if (micName != null && !micName.isBlank()) {
            for (int i = 0; i < mixers.size(); i++) {
                Mixer.Info mixerInfo = mixers.get(i);
                if (micName.equals(mixerInfo.getName())) {
                    selected = mixerInfo;
                    selectedIndex = i;
                    break;
                }
            }
        }
        currentMicMixer = selected;
        currentMicIndex = selectedIndex;
        boolean restart = isHardwareRunning.get();
        if (restart) {
            releaseCaptureHardware();
            sleepQuietly(120);
            ensureHardwareRunning();
        }
    }

    public void switchToNextMic() {
        List<Mixer.Info> mixers = getAvailableMicMixers();
        if (mixers.isEmpty()) return;
        int nextIdx = (currentMicIndex + 1) % mixers.size();
        String nextName = mixers.get(nextIdx).getName();
        selectMic(nextName);
        LOGGER.log(System.Logger.Level.INFO, "Requested microphone switch: " + nextName);
    }

    @Override
    public void releaseCaptureHardware() {
        isRecording.set(false);
        isStreaming.set(false);
        streamChunkConsumer = null;
        ByteArrayOutputStream buffer = audioBuffer;
        audioBuffer = null;
        if (targetDataLine != null) {
            try {
                targetDataLine.stop();
                targetDataLine.close();
            } catch (Exception e) {
                LOGGER.log(System.Logger.Level.ERROR, "Microphone capture channel close failed", e);
            } finally {
                targetDataLine = null;
            }
        }
        isHardwareRunning.set(false);
        if (buffer != null) {
            buffer.reset();
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void executeAudioTask(Runnable task, Runnable onRejected) {
        try {
            executorService.execute(task);
        } catch (RejectedExecutionException rejected) {
            onRejected.run();
            LOGGER.log(System.Logger.Level.WARNING, "Audio task rejected because the bounded worker is unavailable");
        }
    }

    @Override
    public void shutdown() {
        releaseCaptureHardware();
        stopTtsPlayback();

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
