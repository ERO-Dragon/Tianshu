package com.rheinmetal.tianshu.audio;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.api.IAudioBridge;
import org.slf4j.Logger;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class AudioManager implements IAudioBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

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

    private final ExecutorService executorService = Executors.newFixedThreadPool(2);

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
                        LOGGER.info("【白名单命中】锁定物理麦克风: {}", mixerInfo.getName());
                        return mixerInfo;
                    }
                    if (fallbackMic == null)
                        fallbackMic = mixerInfo;
                }
            } catch (Exception e) {
            }
        }
        if (fallbackMic != null)
            LOGGER.warn("【备胎兜底】未找到明确硬件特征，使用: {}", fallbackMic.getName());
        return fallbackMic;
    }

    @Override
    public void ensureHardwareRunning() {
        if (isHardwareRunning.get())
            return;
        isHardwareRunning.set(true);

        executorService.submit(() -> {
            try {
                AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                Mixer.Info bestMixer = currentMicMixer != null ? currentMicMixer : findRealPhysicalMic(info);

                if (bestMixer != null) {
                    targetDataLine = (TargetDataLine) AudioSystem.getMixer(bestMixer).getLine(info);
                    LOGGER.info("常驻模式劫持物理麦克风: {}", bestMixer.getName());
                }
                if (targetDataLine == null) {
                    LOGGER.error("找不到可用的物理麦克风！");
                    isHardwareRunning.set(false);
                    return;
                }

                targetDataLine.open(format);
                targetDataLine.start();
                LOGGER.info("底层麦克风常驻线程启动");

                byte[] rawBuffer = new byte[1600];
                ByteArrayOutputStream streamTempBuffer = new ByteArrayOutputStream();
                int streamChunkCounter = 0;

                while (isHardwareRunning.get()) {
                    int bytesRead = targetDataLine.read(rawBuffer, 0, rawBuffer.length);
                    if (bytesRead <= 0)
                        continue;

                    if (isStreaming.get() && streamChunkConsumer != null) {
                        streamTempBuffer.write(rawBuffer, 0, bytesRead);
                        streamChunkCounter += bytesRead;
                        if (streamChunkCounter >= 3200) {
                            streamChunkConsumer.accept(streamTempBuffer.toByteArray());
                            streamTempBuffer.reset();
                            streamChunkCounter = 0;
                        }
                    } else if (isRecording.get()) {
                        audioBuffer.write(rawBuffer, 0, bytesRead);
                    } else {
                        if (streamTempBuffer.size() > 0) {
                            streamTempBuffer.reset();
                            streamChunkCounter = 0;
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.error("底层麦克风线程异常", e);
            } finally {
                if (targetDataLine != null) {
                    targetDataLine.stop();
                    targetDataLine.close();
                    targetDataLine = null;
                }
                isHardwareRunning.set(false);
                isRecording.set(false);
                isStreaming.set(false);
                LOGGER.info("底层麦克风常驻线程彻底关闭");
            }
        });
    }

    @Override
    public void startRecording() {
        if (isRecording.get() || isStreaming.get())
            return;
        ensureHardwareRunning();
        audioBuffer = new ByteArrayOutputStream();
        isRecording.set(true);
        LOGGER.info("PTT 开始录音");
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
            LOGGER.info("PTT 录音完成，长度: {} bytes", data.length);
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
        LOGGER.info("流式模式启动");
    }

    @Override
    public void stopStreamRecording() {
        isStreaming.set(false);
        this.streamChunkConsumer = null;
        LOGGER.info("流式模式停止");
    }

    public synchronized void playAudio(byte[] audioData) {
        playAudio(audioData, 16000);
    }

    public synchronized void playAudio(byte[] audioData, int sampleRate) {
        if (audioData == null || audioData.length == 0)
            return;
        executorService.submit(() -> {
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
                LOGGER.error("无法获取播放设备", e);
            } catch (Exception e) {
                LOGGER.error("播放音频时发生错误", e);
            } finally {
                if (sourceDataLine != null) {
                    sourceDataLine.stop();
                    sourceDataLine.close();
                    sourceDataLine = null;
                }
                isPlaying.set(false);
            }
        });
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
            LOGGER.info("TTS 播放通道已打开，采样率: {}Hz", sampleRate);
        } catch (LineUnavailableException e) {
            LOGGER.error("无法打开 TTS 播放通道", e);
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
                LOGGER.error("等待 TTS 播放尾音完成异常", e);
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
                LOGGER.error("关闭 TTS 播放通道异常", e);
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
        return names.isEmpty() ? "未检测到麦克风" : names.get(0);
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
        executorService.submit(() -> {
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
        });
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
        LOGGER.info("请求切换麦克风至: {}", nextName);
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
                LOGGER.error("关闭麦克风采集通道异常", e);
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
