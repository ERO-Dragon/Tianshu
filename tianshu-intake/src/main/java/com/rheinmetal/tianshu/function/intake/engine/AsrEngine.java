package com.rheinmetal.tianshu.function.intake.engine;

import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.intake.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.ModelFilesMissingException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns the native recognizer and every stream created from it.
 *
 * <p><b>One lock for the native lifetime.</b> The ONNX recognizer is native memory: releasing it while
 * another thread is still decoding frees that memory underneath the decoder. Teardown therefore cannot be
 * "set the field to null and release": every native call and the release itself run under
 * {@link #nativeLock}. A teardown that arrives in the middle of an utterance waits that one decode out
 * (bounded by the utterance the player just spoke) instead of turning a quit-to-title into a crash.
 *
 * <p>The same lock publishes the fields: readers that do not take it only observe the flow flags
 * ({@link #isOffline()}, {@link #configKind()}), which are volatile for exactly that reason.
 */
public class AsrEngine {
    private static final int SAMPLE_RATE = 16_000;
    /** Zero padding that closes the last chunk of a streaming utterance before its result is read. */
    private static final int STREAMING_TRAILING_SILENCE_SAMPLES = (int) (0.8D * SAMPLE_RATE);
    /** Zero padding that flushes a streaming session before it is reset, so the tail is not lost. */
    private static final int STREAMING_FLUSH_SILENCE_SAMPLES = (int) (0.3D * SAMPLE_RATE);

    public static final class StreamingSession {
        private final OnlineStream stream;

        private StreamingSession(OnlineStream stream) {
            this.stream = stream;
        }
    }

    private final IGameEnvironment env;
    /** Guards every use and the release of the native recognizers; see the class documentation. */
    private final ReentrantLock nativeLock = new ReentrantLock();
    private volatile OnlineRecognizer onlineRecognizer;
    private volatile OfflineRecognizer offlineRecognizer;
    private volatile boolean offline = false;
    private volatile String configKind = "uninitialized";

    public AsrEngine(IGameEnvironment env) {
        this.env = env;
    }

    public boolean initialize(AsrModelInfo modelInfo, Path modelDir) throws ModelFilesMissingException {
        env.info("Initializing ASR engine, model=" + modelInfo.localKey() + ", modelDir=" + modelDir);
        validateRequiredFiles(modelInfo, modelDir);
        try {
            SherpaOnnxAsrConfigFactory factory = new SherpaOnnxAsrConfigFactory(env);
            SherpaOnnxAsrConfigFactory.ResolvedConfig resolved = factory.build(modelInfo, modelDir).orElse(null);
            if (resolved == null) {
                return false;
            }
            nativeLock.lock();
            try {
                applyResolvedConfig(resolved);
            } finally {
                nativeLock.unlock();
            }
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

    /** Replaces whatever this instance held; the caller already owns {@link #nativeLock}. */
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
        nativeLock.lock();
        try {
            if (offline) {
                env.info("ASR engine is offline; continuous input will use segmented recognition");
                return null;
            }
            if (onlineRecognizer == null) {
                env.error("ASR engine is not initialized", null);
                return null;
            }
            OnlineStream stream = onlineRecognizer.createStream();
            return stream == null ? null : new StreamingSession(stream);
        } finally {
            nativeLock.unlock();
        }
    }

    public void releaseStreamingSession(StreamingSession session) {
        if (session == null || session.stream == null) {
            return;
        }
        nativeLock.lock();
        try {
            session.stream.release();
        } finally {
            nativeLock.unlock();
        }
    }

    public void feedAudio(StreamingSession session, byte[] pcmData) {
        if (session == null || session.stream == null || pcmData == null || pcmData.length == 0) {
            return;
        }
        nativeLock.lock();
        try {
            OnlineRecognizer recognizer = onlineRecognizer;
            if (recognizer == null) {
                return;
            }
            session.stream.acceptWaveform(toFloatSamples(pcmData), SAMPLE_RATE);
            while (recognizer.isReady(session.stream)) {
                recognizer.decode(session.stream);
            }
        } finally {
            nativeLock.unlock();
        }
    }

    public String recognizeComplete(byte[] fullAudio) {
        if (fullAudio == null || fullAudio.length == 0) {
            return "";
        }
        float[] samples = toFloatSamples(fullAudio);
        nativeLock.lock();
        try {
            if (offline) {
                return recognizeOffline(samples);
            }
            return recognizeOnline(samples);
        } finally {
            nativeLock.unlock();
        }
    }

    private String recognizeOffline(float[] samples) {
        OfflineRecognizer recognizer = offlineRecognizer;
        if (recognizer == null) {
            return "";
        }
        OfflineStream stream = recognizer.createStream();
        if (stream == null) {
            env.error("ASR offline stream creation failed", null);
            return "";
        }
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE);
            recognizer.decode(stream);
            return recognizer.getResult(stream).getText();
        } finally {
            stream.release();
        }
    }

    private String recognizeOnline(float[] samples) {
        OnlineRecognizer recognizer = onlineRecognizer;
        if (recognizer == null) {
            return "";
        }
        OnlineStream stream = recognizer.createStream();
        if (stream == null) {
            env.error("ASR online stream creation failed", null);
            return "";
        }
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE);
            stream.acceptWaveform(new float[STREAMING_TRAILING_SILENCE_SAMPLES], SAMPLE_RATE);
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream);
            }
            return recognizer.getResult(stream).getText();
        } finally {
            stream.release();
        }
    }

    public String forceFlush(StreamingSession session) {
        if (session == null || session.stream == null) {
            return "";
        }
        nativeLock.lock();
        try {
            OnlineRecognizer recognizer = onlineRecognizer;
            if (recognizer == null) {
                return "";
            }
            session.stream.acceptWaveform(new float[STREAMING_FLUSH_SILENCE_SAMPLES], SAMPLE_RATE);
            while (recognizer.isReady(session.stream)) {
                recognizer.decode(session.stream);
            }
            String result = recognizer.getResult(session.stream).getText();
            recognizer.reset(session.stream);
            return result;
        } finally {
            nativeLock.unlock();
        }
    }

    /**
     * PCM16LE bytes to normalised samples.
     *
     * <p>The low byte is read unsigned and combined with the signed high byte, which is the only way the
     * two halves of a little-endian sample reconstruct its value; treating the low byte as signed instead
     * shifts every sample whose low bit 7 is set down by 256 (about 0.8% of full scale).
     */
    private float[] toFloatSamples(byte[] pcmData) {
        int sampleCount = pcmData.length / 2;
        float[] samples = new float[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            int low = pcmData[2 * i] & 0xFF;
            int high = pcmData[2 * i + 1];
            samples[i] = (short) ((high << 8) | low) / 32768.0F;
        }
        return samples;
    }

    public void shutdown() {
        nativeLock.lock();
        try {
            OnlineRecognizer online = onlineRecognizer;
            OfflineRecognizer offlineRecognizerToRelease = offlineRecognizer;
            onlineRecognizer = null;
            offlineRecognizer = null;
            offline = false;
            configKind = "uninitialized";
            boolean onlineReleased = online == null
                    || releaseRecognizer("asr.engine.online_release_failed", online::release);
            boolean offlineReleased = offlineRecognizerToRelease == null
                    || releaseRecognizer("asr.engine.offline_release_failed", offlineRecognizerToRelease::release);
            if (onlineReleased && offlineReleased) {
                env.info("ASR engine closed safely");
            }
        } finally {
            nativeLock.unlock();
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