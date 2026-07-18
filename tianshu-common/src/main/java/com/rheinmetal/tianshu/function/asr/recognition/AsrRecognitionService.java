package com.rheinmetal.tianshu.function.asr.recognition;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.function.asr.AsrProtocolAdapter;
import com.rheinmetal.tianshu.function.asr.engine.AsrEngine;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.Map;

public final class AsrRecognitionService {
    private final IGameEnvironment env;
    private final Supplier<AsrEngine> engineSupplier;
    private final AsrProtocolAdapter adapter;
    private volatile StreamingRuntime streamingRuntime;
    private volatile ProtocolTaskHandle streamingTask;
    private volatile ProtocolTaskHandle completeTask;

    public AsrRecognitionService(IGameEnvironment env, Supplier<AsrEngine> engineSupplier, AsrProtocolAdapter adapter) {
        this.env = env;
        this.engineSupplier = engineSupplier;
        this.adapter = adapter;
    }

    public void recognizeComplete(byte[] audioData, long sessionId, String inputMode, Consumer<AsrRecognitionResult> onResult, Runnable onComplete) {
        cancelCompleteTask();
        completeTask = adapter.submitRecognitionTask("complete", () -> {
            try {
                env.info("asr.recognition.complete.started bytes=" + (audioData == null ? 0 : audioData.length));
                String result = engine().recognizeComplete(audioData);
                if (isMeaningfulText(result)) {
                    publishDiagnostic("RECOGNITION_COMPLETE", DiagnosticSeverity.INFO, result, inputMode, sessionId);
                    onResult.accept(new AsrRecognitionResult(result, result, sessionId, inputMode));
                } else {
                    env.info("asr.recognition.complete.empty");
                }
            } catch (Exception e) {
                env.error("asr.recognition.complete.failed", e);
            } finally {
                onComplete.run();
            }
        });
    }

    public boolean startStreaming(long sessionId, Consumer<AsrRecognitionResult> onResult) {
        return startStreaming(sessionId, onResult, false);
    }

    public boolean startStreaming(long sessionId, Consumer<AsrRecognitionResult> onResult, boolean vadEnabled) {
        StreamingRuntime current = streamingRuntime;
        if (current != null && current.accepts(sessionId)) {
            return true;
        }
        if (current != null) {
            stopStreaming();
        }
        AsrEngine engine = engine();
        AsrEngine.StreamingSession createdSession = engine.createStreamingSession();
        if (createdSession == null && !engine.supportsCompleteRecognition()) {
            env.warn("asr.recognition.continuous.session_create_failed");
            return false;
        }
        StreamingRuntime runtime = new StreamingRuntime(sessionId, createdSession, vadEnabled);
        streamingRuntime = runtime;
        streamingTask = adapter.submitRecognitionTask("stream", () -> processStreaming(runtime, onResult));
        return true;
    }

    public void acceptAudioChunk(byte[] chunk, long sessionId) {
        acceptAudioChunk(chunk, sessionId, AsrSpeechSegmenter.Decision.CONTINUE);
    }

    public void acceptAudioChunk(byte[] chunk, long sessionId, AsrSpeechSegmenter.Decision decision) {
        if (chunk == null || chunk.length == 0) {
            return;
        }
        StreamingRuntime runtime = streamingRuntime;
        if (runtime != null && runtime.accepts(sessionId)) {
            runtime.offer(StreamCommand.audio(chunk, decision));
        }
    }

    public void forceFlush(long sessionId) {
        StreamingRuntime runtime = streamingRuntime;
        if (runtime != null && runtime.accepts(sessionId)) {
            runtime.offer(StreamCommand.flush());
        }
    }

    public void stopStreaming() {
        StreamingRuntime runtime = streamingRuntime;
        if (runtime == null) {
            return;
        }
        runtime.close();
        runtime.offer(StreamCommand.stop());
        if (streamingRuntime == runtime) {
            streamingRuntime = null;
        }
        ProtocolTaskHandle task = streamingTask;
        streamingTask = null;
        if (task != null && !task.isDone() && !task.isRunning()) {
            task.cancel("ASR stream stopped");
            releaseStreamingSession(runtime);
        }
    }

    public void stopAll() {
        cancelCompleteTask();
        stopStreaming();
    }

    public boolean isStreaming() {
        StreamingRuntime runtime = streamingRuntime;
        return runtime != null && runtime.open();
    }

    private void processStreaming(StreamingRuntime runtime, Consumer<AsrRecognitionResult> onResult) {
        try {
            while (isCurrentRuntime(runtime)) {
                StreamCommand command = runtime.take();
                if (command.type() == StreamCommandType.STOP) {
                    break;
                }
                if (command.type() == StreamCommandType.FLUSH) {
                    flushIfAudio(runtime, "force_flush", onResult);
                    continue;
                }
                if (!runtime.acceptAudio(command.decision())) {
                    continue;
                }
                runtime.markAudio();
                if (runtime.online()) {
                    engine().feedAudio(runtime.engineSession(), command.audio());
                    if (command.decision().endsSegment()) {
                        flushIfAudio(runtime, "vad_segment", onResult);
                    }
                } else {
                    runtime.appendSegmentAudio(command.audio());
                    if (command.decision().endsSegment()) {
                        flushIfAudio(runtime, "vad_segment", onResult);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            env.error("asr.recognition.continuous.failed", e);
        } finally {
            releaseStreamingSession(runtime);
        }
    }

    private void flushIfAudio(StreamingRuntime runtime, String inputMode, Consumer<AsrRecognitionResult> onResult) {
        if (!runtime.hasAudio()) {
            return;
        }
        publishStreamingResult(flushRuntime(runtime), runtime, inputMode, onResult);
        runtime.resetAfterFlush();
    }

    private String flushRuntime(StreamingRuntime runtime) {
        if (runtime.online()) {
            return engine().forceFlush(runtime.engineSession());
        }
        byte[] audio = runtime.drainSegmentAudio();
        if (audio.length == 0) {
            return "";
        }
        return engine().recognizeComplete(audio);
    }

    private void publishStreamingResult(String text, StreamingRuntime runtime, String inputMode, Consumer<AsrRecognitionResult> onResult) {
        if (!isCurrentRuntime(runtime)) {
            return;
        }
        AsrRecognitionResult result = normalizeStreamingText(text, runtime.sessionId(), inputMode);
        if (result != null && result.hasText() && isCurrentRuntime(runtime)) {
            publishDiagnostic("STREAM_RESULT", DiagnosticSeverity.INFO, result.text(), inputMode, runtime.sessionId());
            onResult.accept(result);
        }
    }

    private void publishDiagnostic(String code, DiagnosticSeverity severity, String text, String inputMode, long sessionId) {
        env.diagnostics().publish(DiagnosticEvent.now(
                AsrProtocolAdapter.MODULE_ID,
                code,
                severity,
                DiagnosticPrivacy.RAW_CONTENT,
                Map.of(
                        "sessionId", Long.toString(sessionId),
                        "inputMode", inputMode == null ? "" : inputMode,
                        "text", text == null ? "" : text
                )
        ));
    }

    private AsrRecognitionResult normalizeStreamingText(String text, long sessionId, String inputMode) {
        if (!isMeaningfulText(text)) {
            return null;
        }
        return new AsrRecognitionResult(text, text, sessionId, inputMode);
    }

    private void cancelCompleteTask() {
        ProtocolTaskHandle task = completeTask;
        if (task != null && !task.isDone()) {
            task.cancel("ASR complete recognition cancelled");
        }
        completeTask = null;
    }

    private boolean isCurrentRuntime(StreamingRuntime runtime) {
        return runtime != null && runtime.open() && streamingRuntime == runtime;
    }

    private void releaseStreamingSession(StreamingRuntime runtime) {
        if (runtime == null || !runtime.release()) {
            return;
        }
        if (streamingRuntime == runtime) {
            streamingRuntime = null;
        }
        if (runtime.online()) {
            try {
                engine().releaseStreamingSession(runtime.engineSession());
            } catch (Exception e) {
                env.error("asr.recognition.continuous.session_close_failed", e);
            }
        }
    }

    private AsrEngine engine() {
        AsrEngine engine = engineSupplier.get();
        if (engine == null) {
            throw new IllegalStateException("ASR engine is not ready");
        }
        return engine;
    }

    private boolean isMeaningfulText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String cleanText = text.replaceAll("[\\p{P}\\s\\p{C}]", "");
        return cleanText.length() >= 1;
    }

    private enum StreamCommandType {
        AUDIO,
        FLUSH,
        STOP
    }

    private record StreamCommand(StreamCommandType type, byte[] audio, AsrSpeechSegmenter.Decision decision) {
        static StreamCommand audio(byte[] audio, AsrSpeechSegmenter.Decision decision) {
            return new StreamCommand(StreamCommandType.AUDIO, audio, decision == null ? AsrSpeechSegmenter.Decision.CONTINUE : decision);
        }

        static StreamCommand flush() {
            return new StreamCommand(StreamCommandType.FLUSH, null, AsrSpeechSegmenter.Decision.CONTINUE);
        }

        static StreamCommand stop() {
            return new StreamCommand(StreamCommandType.STOP, null, AsrSpeechSegmenter.Decision.CONTINUE);
        }
    }

    private static final class StreamingRuntime {
        private final long sessionId;
        private final AsrEngine.StreamingSession engineSession;
        private final boolean vadEnabled;
        private final ByteArrayOutputStream segmentBuffer = new ByteArrayOutputStream();
        private final BlockingQueue<StreamCommand> commands = new LinkedBlockingQueue<>();
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final AtomicBoolean released = new AtomicBoolean(false);
        private boolean segmentActive;
        private boolean hasAudio;

        private StreamingRuntime(long sessionId, AsrEngine.StreamingSession engineSession, boolean vadEnabled) {
            this.sessionId = sessionId;
            this.engineSession = engineSession;
            this.vadEnabled = vadEnabled;
        }

        private boolean accepts(long sessionId) {
            return open() && this.sessionId == sessionId;
        }

        private boolean open() {
            return open.get();
        }

        private long sessionId() {
            return sessionId;
        }

        private AsrEngine.StreamingSession engineSession() {
            return engineSession;
        }

        private boolean online() {
            return engineSession != null;
        }

        private boolean acceptAudio(AsrSpeechSegmenter.Decision decision) {
            AsrSpeechSegmenter.Decision actual = decision == null ? AsrSpeechSegmenter.Decision.CONTINUE : decision;
            if (vadEnabled && actual.startsSegment()) {
                segmentActive = true;
            }
            return !vadEnabled || segmentActive;
        }

        private void appendSegmentAudio(byte[] audio) {
            if (audio == null || audio.length == 0) {
                return;
            }
            synchronized (segmentBuffer) {
                segmentBuffer.write(audio, 0, audio.length);
            }
            hasAudio = true;
        }

        private void markAudio() {
            hasAudio = true;
        }

        private byte[] drainSegmentAudio() {
            synchronized (segmentBuffer) {
                byte[] audio = segmentBuffer.toByteArray();
                segmentBuffer.reset();
                return audio;
            }
        }

        private boolean hasAudio() {
            return hasAudio;
        }

        private void resetAfterFlush() {
            hasAudio = false;
            segmentActive = false;
        }

        private void close() {
            open.set(false);
        }

        private boolean release() {
            close();
            return released.compareAndSet(false, true);
        }

        private void offer(StreamCommand command) {
            try {
                commands.put(command);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private StreamCommand take() throws InterruptedException {
            return commands.take();
        }
    }
}
