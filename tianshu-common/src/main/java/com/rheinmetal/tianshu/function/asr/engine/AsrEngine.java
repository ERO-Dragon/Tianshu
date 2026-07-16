package com.rheinmetal.tianshu.function.asr.engine;

import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.ModelFilesMissingException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class AsrEngine {

    public static final class StreamingSession {
        private final OnlineStream stream;
        private final ReentrantLock lock = new ReentrantLock();

        private StreamingSession(OnlineStream stream) {
            this.stream = stream;
        }
    }

    private final IGameEnvironment env;
    private OnlineRecognizer onlineRecognizer;
    private OfflineRecognizer offlineRecognizer;
    private boolean offline = false;
    private String configKind = "uninitialized";

    public AsrEngine(IGameEnvironment env) {
        this.env = env;
    }

    public boolean initialize(AsrModelInfo modelInfo, Path modelDir) throws ModelFilesMissingException {
        return initialize(modelInfo, modelDir, null);
    }

    public boolean initialize(AsrModelInfo modelInfo, Path modelDir, Path hotwordsFile) throws ModelFilesMissingException {
        env.info("Initializing ASR engine, model=" + modelInfo.localKey() + ", modelDir=" + modelDir);
        validateRequiredFiles(modelInfo, modelDir);
        try {
            SherpaOnnxAsrConfigFactory factory = new SherpaOnnxAsrConfigFactory(env);
            SherpaOnnxAsrConfigFactory.ResolvedConfig resolved = factory.build(modelInfo, modelDir, hotwordsFile).orElse(null);
            if (resolved == null) {
                return false;
            }
            applyResolvedConfig(resolved);
            env.info("ASR engine initialized (kind=" + configKind + ", offline=" + offline + ")");
            return true;
        } catch (RuntimeException | LinkageError failure) {
            env.error("ASR engine creation failed; check whether model files are valid", failure);
            shutdown();
            return false;
        }
    }

    private void validateRequiredFiles(AsrModelInfo modelInfo, Path modelDir) throws ModelFilesMissingException {
        List<String> missing = new ArrayList<>();
        for (String file : modelInfo.getAllRequiredFiles()) {
            Path path = modelDir.resolve(file).normalize();
            if (!path.toAbsolutePath().normalize().startsWith(modelDir.toAbsolutePath().normalize())
                    || !Files.isRegularFile(path)) {
                missing.add(file);
            }
        }
        if (!missing.isEmpty()) {
            throw new ModelFilesMissingException(modelInfo.localKey(), missing);
        }
    }

    private void applyResolvedConfig(SherpaOnnxAsrConfigFactory.ResolvedConfig resolved) {
        shutdown();
        this.offline = resolved.offline();
        this.configKind = resolved.kind();
        if (resolved.offline()) {
            offlineRecognizer = new OfflineRecognizer(resolved.offlineConfig());
        } else {
            onlineRecognizer = new OnlineRecognizer(resolved.onlineConfig());
        }
    }

    public boolean isOffline() {
        return offline;
    }

    public boolean supportsStreamingRecognition() {
        return !offline && onlineRecognizer != null;
    }

    public boolean supportsCompleteRecognition() {
        return offlineRecognizer != null || onlineRecognizer != null;
    }

    public String configKind() {
        return configKind;
    }

    public StreamingSession createStreamingSession() {
        if (offline) {
            env.info("ASR engine is offline; continuous input will use segmented recognition");
            return null;
        }
        if (onlineRecognizer == null) {
            env.error("ASR engine is not initialized", null);
            return null;
        }
        return new StreamingSession(onlineRecognizer.createStream());
    }

    public void releaseStreamingSession(StreamingSession session) {
        if (session == null) {
            return;
        }
        session.lock.lock();
        try {
            session.stream.release();
        } finally {
            session.lock.unlock();
        }
    }

    public void feedAudio(StreamingSession session, byte[] pcmData) {
        if (onlineRecognizer == null || session == null || pcmData == null || pcmData.length == 0) return;
        session.lock.lock();
        try {
            float[] samples = toFloatSamples(pcmData);
            session.stream.acceptWaveform(samples, 16000);
            while (onlineRecognizer.isReady(session.stream)) {
                onlineRecognizer.decode(session.stream);
            }
        } finally {
            session.lock.unlock();
        }
    }

    public String recognizeComplete(byte[] fullAudio) {
        if (fullAudio == null || fullAudio.length == 0) {
            return "";
        }
        float[] samples = toFloatSamples(fullAudio);
        if (offline && offlineRecognizer != null) {
            OfflineStream tempStream = offlineRecognizer.createStream();
            tempStream.acceptWaveform(samples, 16000);
            offlineRecognizer.decode(tempStream);
            String result = offlineRecognizer.getResult(tempStream).getText();
            tempStream.release();
            return result;
        }

        if (onlineRecognizer == null) return "";
        OnlineStream tempStream = onlineRecognizer.createStream();
        tempStream.acceptWaveform(samples, 16000);
        tempStream.acceptWaveform(new float[(int) (0.8 * 16000)], 16000);
        while (onlineRecognizer.isReady(tempStream)) {
            onlineRecognizer.decode(tempStream);
        }
        String result = onlineRecognizer.getResult(tempStream).getText();
        tempStream.release();
        return result;
    }

    public String forceFlush(StreamingSession session) {
        if (onlineRecognizer == null || session == null) return "";
        session.lock.lock();
        try {
            session.stream.acceptWaveform(new float[(int) (0.3 * 16000)], 16000);
            while (onlineRecognizer.isReady(session.stream)) {
                onlineRecognizer.decode(session.stream);
            }
            String result = onlineRecognizer.getResult(session.stream).getText();
            onlineRecognizer.reset(session.stream);
            return result;
        } finally {
            session.lock.unlock();
        }
    }

    private float[] toFloatSamples(byte[] pcmData) {
        float[] samples = new float[pcmData.length / 2];
        for (int i = 0; i != samples.length; ++i) {
            short low = (short) pcmData[2 * i];
            short high = (short) pcmData[2 * i + 1];
            int s = (high << 8) + low;
            samples[i] = (float) s / 32768;
        }
        return samples;
    }

    public void shutdown() {
        OnlineRecognizer online = onlineRecognizer;
        OfflineRecognizer offlineRecognizerToRelease = offlineRecognizer;
        onlineRecognizer = null;
        offlineRecognizer = null;
        offline = false;
        configKind = "uninitialized";
        boolean onlineReleased = online == null
                || releaseRecognizer("tianshu.asr.engine.online_release_failed", online::release);
        boolean offlineReleased = offlineRecognizerToRelease == null
                || releaseRecognizer("tianshu.asr.engine.offline_release_failed", offlineRecognizerToRelease::release);
        if (onlineReleased && offlineReleased) {
            env.info("ASR engine closed safely");
        }
    }

    private boolean releaseRecognizer(String diagnosticCode, Runnable release) {
        try {
            release.run();
            return true;
        } catch (RuntimeException | LinkageError failure) {
            env.error(diagnosticCode, failure);
            return false;
        }
    }

}
