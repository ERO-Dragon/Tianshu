package com.rheinmetal.tianshu.function.asr.audio;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;

import java.util.function.Consumer;

public final class AudioCaptureService {
    private final IAudioBridge audioBridge;
    private final IGameEnvironment env;
    private volatile AudioFrameProcessor frameProcessor = AudioFrameProcessor.identity();

    public AudioCaptureService(IAudioBridge audioBridge, IGameEnvironment env) {
        this.audioBridge = audioBridge;
        this.env = env;
    }

    public void setFrameProcessor(AudioFrameProcessor frameProcessor) {
        this.frameProcessor = frameProcessor == null ? AudioFrameProcessor.identity() : frameProcessor;
    }

    public void startPttCapture() {
        stopStreamCapture();
        audioBridge.startRecording();
    }

    public byte[] stopPttCapture() {
        return frameProcessor.process(audioBridge.stopRecording());
    }

    public void startStreamCapture(Consumer<byte[]> consumer) {
        audioBridge.startStreamRecording(chunk -> {
            byte[] processed = frameProcessor.process(chunk);
            if (processed != null && processed.length > 0) {
                consumer.accept(processed);
            }
        });
    }

    public void stopStreamCapture() {
        audioBridge.stopStreamRecording();
    }

    public void stopAll() {
        try {
            audioBridge.stopRecording();
        } catch (Throwable t) {
            env.error("停止 ASR PTT 录音失败", t);
        }
        try {
            audioBridge.stopStreamRecording();
        } catch (Throwable t) {
            env.error("停止 ASR 流式录音失败", t);
        }
    }

    public void releaseHardware() {
        stopAll();
        try {
            audioBridge.releaseCaptureHardware();
        } catch (Throwable t) {
            env.error("释放 ASR 麦克风采集硬件失败", t);
        }
    }
}
