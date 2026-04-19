package com.rheinmetal.tianshu.audio;

import com.rheinmetal.tianshu.Tianshu;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

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

public class AudioManager {
    private TargetDataLine targetDataLine;
    private SourceDataLine sourceDataLine;
    private SourceDataLine ttsDataLine;
    private final AtomicInteger ttsPlaybackTurnId = new AtomicInteger(0);

    // 【版本 B 核心】分离硬件状态和业务状态
    private final AtomicBoolean isHardwareRunning = new AtomicBoolean(false); // 底层声卡是否常驻
    private final AtomicBoolean isRecording = new AtomicBoolean(false); // PTT 按住状态
    private final AtomicBoolean isStreaming = new AtomicBoolean(false); // 流式模式状态
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);

    private ByteArrayOutputStream audioBuffer; // PTT 专属缓存区
    private Consumer<byte[]> streamChunkConsumer; // 流式模式回调

    private final ExecutorService executorService = Executors.newFixedThreadPool(2);

    // 精准劫持算法 (白名单 + 终极黑名单)
    private Mixer.Info findRealPhysicalMic(DataLine.Info info) {
        Mixer.Info fallbackMic = null;
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            String name = mixerInfo.getName().toLowerCase();
            String desc = mixerInfo.getDescription().toLowerCase();

            // 终极黑名单
            if (name.contains("主声音捕获") || // 必须干掉！这是导致Windows降音的罪魁祸首
                    name.contains("软件") || name.contains("software") || name.contains("回环") || name.contains("loopback")
                    || name.contains("立体声混音") || name.contains("stereo mix") || name.contains("虚拟音频")
                    || name.contains("virtual audio cable") || name.contains("wave out")) {
                continue;
            }

            try {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                if (mixer.isLineSupported(info)) {
                    // 白名单：只要描述里带硬件特征，100%是真物理麦
                    if (desc.contains("high definition audio") || desc.contains("usb audio") ||
                            desc.contains("realtek") || name.contains("usb") || desc.contains("logitech") ||
                            desc.contains("razer") || desc.contains("hyperx") || desc.contains("steelseries")) {
                        Tianshu.LOGGER.info("【白名单命中】锁定物理麦克风: {}", mixerInfo.getName());
                        return mixerInfo;
                    }
                    if (fallbackMic == null)
                        fallbackMic = mixerInfo;
                }
            } catch (Exception e) {
            }
        }
        if (fallbackMic != null)
            Tianshu.LOGGER.warn("【备胎兜底】未找到明确硬件特征，使用: {}", fallbackMic.getName());
        return fallbackMic;
    }

    // 启动底层硬件保活线程（私有方法，整个生命周期只执行一次）
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
                    Tianshu.LOGGER.info("常驻模式劫持物理麦克风: {}", bestMixer.getName());
                }
                if (targetDataLine == null) {
                    Tianshu.LOGGER.error("找不到可用的物理麦克风！");
                    isHardwareRunning.set(false);
                    return;
                }

                targetDataLine.open(format);
                targetDataLine.start();
                Tianshu.LOGGER.info("底层麦克风常驻线程启动");

                byte[] rawBuffer = new byte[1600]; // 50ms 的数据块
                // 【关键优化】流式和 PTT 的缓冲区物理隔离，彻底杜绝切换模式时数据串流
                ByteArrayOutputStream streamTempBuffer = new ByteArrayOutputStream();
                int streamChunkCounter = 0;

                // 唯一的底层死循环，永不主动退出
                while (isHardwareRunning.get()) {
                    int bytesRead = targetDataLine.read(rawBuffer, 0, rawBuffer.length);
                    if (bytesRead <= 0)
                        continue;

                    if (isStreaming.get() && streamChunkConsumer != null) {
                        // --- 流式模式通道 ---
                        streamTempBuffer.write(rawBuffer, 0, bytesRead);
                        streamChunkCounter += bytesRead;
                        if (streamChunkCounter >= 3200) { // 攒够 100ms 发一次
                            streamChunkConsumer.accept(streamTempBuffer.toByteArray());
                            streamTempBuffer.reset();
                            streamChunkCounter = 0;
                        }
                    } else if (isRecording.get()) {
                        // --- PTT 模式通道 ---
                        audioBuffer.write(rawBuffer, 0, bytesRead);
                    } else {
                        // --- 空闲状态 ---
                        // 什么都不做，新数据直接覆盖 rawBuffer。
                        // 如果从流式切过来，顺手把流式残留冲掉
                        if (streamTempBuffer.size() > 0) {
                            streamTempBuffer.reset();
                            streamChunkCounter = 0;
                        }
                    }
                }
            } catch (Exception e) {
                Tianshu.LOGGER.error("底层麦克风线程异常", e);
            } finally {
                // 只有退出世界触发 shutdown() 时，才会走到这里安全关闭
                if (targetDataLine != null) {
                    targetDataLine.stop();
                    targetDataLine.close();
                    targetDataLine = null;
                }
                isHardwareRunning.set(false);
                isRecording.set(false);
                isStreaming.set(false);
                Tianshu.LOGGER.info("底层麦克风常驻线程彻底关闭");
            }
        });
    }

    // ================= PTT 模式接口 =================
    public void startRecording() {
        if (isRecording.get() || isStreaming.get())
            return;
        ensureHardwareRunning(); // 确保底层在跑
        audioBuffer = new ByteArrayOutputStream(); // 拿个新袋子装新声音
        isRecording.set(true); // 通知底层：开始往袋子里装
        Tianshu.LOGGER.info("PTT 开始录音");
    }

    public byte[] stopRecording() {
        isRecording.set(false); // 只是停止装数据，底层硬件继续跑！
        try {
            Thread.sleep(60);
        } catch (InterruptedException e) {
        } // 等 60ms 让底层把最后一点声音存完
        if (audioBuffer != null) {
            byte[] data = audioBuffer.toByteArray();
            Tianshu.LOGGER.info("PTT 录音完成，长度: {} bytes", data.length);
            return data;
        }
        return new byte[0];
    }

    // ================= 流式模式接口 =================
    public void startStreamRecording(Consumer<byte[]> onAudioChunk) {
        if (isStreaming.get() || isRecording.get())
            return;
        this.streamChunkConsumer = onAudioChunk;
        ensureHardwareRunning(); // 确保底层在跑
        isStreaming.set(true);
        Tianshu.LOGGER.info("流式模式启动");
    }

    public void stopStreamRecording() {
        isStreaming.set(false);
        this.streamChunkConsumer = null;
        Tianshu.LOGGER.info("流式模式停止");
    }

    // ================= 播放与状态查询 =================
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
                Tianshu.LOGGER.error("无法获取播放设备", e);
            } catch (Exception e) {
                Tianshu.LOGGER.error("播放音频时发生错误", e);
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

    public void startTtsPlayback(int sampleRate) {
        stopTtsPlayback();
        int turnId = ttsPlaybackTurnId.incrementAndGet();
        executorService.submit(() -> {
            try {
                AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                if (!AudioSystem.isLineSupported(info))
                    return;
                ttsDataLine = (SourceDataLine) AudioSystem.getLine(info);
                ttsDataLine.open(format);
                ttsDataLine.start();
                Tianshu.LOGGER.info("TTS 播放通道已打开，采样率: {}Hz", sampleRate);
            } catch (LineUnavailableException e) {
                Tianshu.LOGGER.error("无法打开 TTS 播放通道", e);
            }
        });
    }

    public void feedTtsAudio(byte[] audioData) {
        if (ttsDataLine == null || !ttsDataLine.isOpen()) return;
        if (audioData == null || audioData.length == 0) return;
        ttsDataLine.write(audioData, 0, audioData.length);
    }

    public void stopTtsPlayback() {
        if (ttsDataLine != null) {
            try {
                ttsDataLine.drain();
                ttsDataLine.stop();
                ttsDataLine.close();
            } catch (Exception e) {
                Tianshu.LOGGER.error("关闭 TTS 播放通道异常", e);
            }
            ttsDataLine = null;
        }
    }

    public void stopPlayback() {
        isPlaying.set(false);
    }

    public boolean isRecording() {
        return isRecording.get();
    }

    public boolean isPlaying() {
        return isPlaying.get();
    }

    public boolean isStreaming() {
        return isStreaming.get();
    }

    private volatile Mixer.Info currentMicMixer = null;
    private volatile int currentMicIndex = -1;

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

    public void switchToNextMic() {
        List<Mixer.Info> mixers = getAvailableMicMixers();
        if (mixers.isEmpty()) return;
        int nextIdx = (currentMicIndex + 1) % mixers.size();
        currentMicIndex = nextIdx;
        currentMicMixer = mixers.get(nextIdx);
        Tianshu.LOGGER.info("切换麦克风至: {}", currentMicMixer.getName());

        boolean wasRunning = isHardwareRunning.get();
        if (wasRunning) {
            isHardwareRunning.set(false);
            if (targetDataLine != null) {
                targetDataLine.stop();
                targetDataLine.close();
                targetDataLine = null;
            }
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            ensureHardwareRunning();
        }
    }

    // ================= 资源销毁 (只有退出世界才调用) =================
    public void shutdown() {
        isRecording.set(false);
        isStreaming.set(false);
        isHardwareRunning.set(false);
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
