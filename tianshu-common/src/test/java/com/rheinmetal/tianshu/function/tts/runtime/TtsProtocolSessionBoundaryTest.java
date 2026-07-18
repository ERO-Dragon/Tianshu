package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsAudioSink;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisEngine;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackPlacement;
import com.rheinmetal.tianshu.protocol.payload.TtsRequestStatus;
import com.rheinmetal.tianshu.protocol.payload.TtsRequestStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsTextInputMode;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsProtocolSessionBoundaryTest {
    private final ProtocolExecutorManager executorManager = new ProtocolExecutorManager(Runnable::run);

    @AfterEach
    void closeExecutor() {
        executorManager.close();
    }

    @Test
    void laterChunksCannotChangePlacementFrozenAtAdmission() throws Exception {
        BlockingFirstSynthesisEngine engine = new BlockingFirstSynthesisEngine();
        List<TtsRequestStatusPayload> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, statuses);
        prepare(runtime);

        TtsSpeechSessionKey active = TtsSpeechSessionKey.of("module.a", 1L, 1, "active");
        TtsSpeechSessionKey stream = TtsSpeechSessionKey.of("module.ax", 2L, 1, "stream");
        assertTrue(runtime.submitSpeech(active, TtsTextInputMode.DOCUMENT, true,
                request("active", "A.", "module.a", TtsPlaybackPlacement.QUEUE_AFTER_SESSION)).accepted());
        assertTrue(engine.firstStarted.await(2L, TimeUnit.SECONDS));

        assertTrue(runtime.submitSpeech(stream, TtsTextInputMode.SENTENCE_STREAM, false,
                request("stream", "B1.", "module.ax", TtsPlaybackPlacement.QUEUE_AFTER_SESSION)).accepted());
        assertTrue(runtime.submitSpeech(stream, TtsTextInputMode.SENTENCE_STREAM, true,
                request("stream", "B2.", "module.ax", TtsPlaybackPlacement.CANCEL_SESSION_AND_PLAY)).accepted());

        engine.releaseFirst.countDown();
        assertTrue(engine.awaitInvocations(3));
        assertEquals(List.of("A.", "B1.", "B2."), engine.invocationTexts());
        assertEquals(1L, statuses.stream()
                .filter(status -> status.sessionId() == 2L && status.status() == TtsRequestStatus.QUEUED)
                .count());
    }

    @Test
    void admissionReturnsBeforePlaybackCompletesAndStatusKeepsLogicalIdentity() throws Exception {
        BlockingFirstSynthesisEngine engine = new BlockingFirstSynthesisEngine();
        List<TtsRequestStatusPayload> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, statuses);
        prepare(runtime);
        TtsSpeechSessionKey key = TtsSpeechSessionKey.of("module.ax", 21L, 7, "trace");

        TtsOperationResult result = runtime.submitSpeech(key, TtsTextInputMode.DOCUMENT, true,
                request("ax-turn", "Hello.", "module.ax", TtsPlaybackPlacement.QUEUE_AFTER_SESSION));

        assertTrue(result.accepted());
        assertTrue(engine.firstStarted.await(2L, TimeUnit.SECONDS));
        assertTrue(statuses.stream().anyMatch(status -> status.requestId().equals("ax-turn")
                && status.sourceId().equals("module.ax")
                && status.sessionId() == 21L
                && status.turnId() == 7
                && status.status() == TtsRequestStatus.PLAYING));
        engine.releaseFirst.countDown();
    }

    @Test
    void cancelledOpenStreamIgnoresLateChunksUntilEndThenAllowsKeyReuse() throws Exception {
        BlockingFirstSynthesisEngine engine = new BlockingFirstSynthesisEngine();
        TtsRuntime runtime = runtime(engine, Collections.synchronizedList(new ArrayList<>()));
        prepare(runtime);
        TtsSpeechSessionKey interrupted = TtsSpeechSessionKey.of("module.a", 31L, 1, "interrupted");
        TtsSpeechSessionKey replacement = TtsSpeechSessionKey.of("module.b", 32L, 1, "replacement");

        assertTrue(runtime.submitSpeech(interrupted, TtsTextInputMode.RAW_TEXT_STREAM, false,
                request("interrupted", "A1.", "module.a", TtsPlaybackPlacement.QUEUE_AFTER_SESSION)).accepted());
        assertTrue(engine.firstStarted.await(2L, TimeUnit.SECONDS));
        assertTrue(runtime.submitSpeech(replacement, TtsTextInputMode.DOCUMENT, true,
                request("replacement", "B.", "module.b", TtsPlaybackPlacement.CANCEL_SESSION_AND_PLAY)).accepted());

        assertTrue(runtime.submitSpeech(interrupted, TtsTextInputMode.SENTENCE_STREAM, false,
                request("late", "ignored", "module.a", TtsPlaybackPlacement.QUEUE_AFTER_SESSION)).accepted());
        assertTrue(runtime.submitSpeech(interrupted, TtsTextInputMode.SENTENCE_STREAM, true,
                request("late-end", "", "module.a", TtsPlaybackPlacement.QUEUE_AFTER_SESSION)).accepted());
        assertTrue(runtime.submitSpeech(interrupted, TtsTextInputMode.SENTENCE_STREAM, true,
                request("reused", "A2.", "module.a", TtsPlaybackPlacement.QUEUE_AFTER_SESSION)).accepted());

        assertTrue(engine.awaitInvocations(3));
        assertEquals(List.of("A1.", "B.", "A2."), engine.invocationTexts());
    }

    @Test
    void cancellingQueuedSpeechDoesNotInterruptActivePureSynthesis() throws Exception {
        BackgroundBlockingSynthesisEngine engine = new BackgroundBlockingSynthesisEngine();
        TtsRuntime runtime = runtime(engine, Collections.synchronizedList(new ArrayList<>()));
        prepare(runtime);
        TtsSpeechSessionKey queued = TtsSpeechSessionKey.of("module.a", 41L, 1, "queued");
        TtsSpeechSessionKey replacement = TtsSpeechSessionKey.of("module.b", 42L, 1, "replacement");

        try {
            assertTrue(runtime.synthesize(
                    request("background", "background.", "module.task", TtsPlaybackPlacement.QUEUE_AFTER_SESSION),
                    false,
                    30_000L,
                    (index, audio, last) -> { },
                    () -> { },
                    failure -> { }
            ).accepted());
            assertTrue(engine.backgroundStarted.await(2L, TimeUnit.SECONDS));
            assertTrue(runtime.submitSpeech(queued, TtsTextInputMode.DOCUMENT, true,
                    request("queued", "A.", "module.a", TtsPlaybackPlacement.QUEUE_AFTER_SESSION)).accepted());
            assertTrue(runtime.submitSpeech(replacement, TtsTextInputMode.DOCUMENT, true,
                    request("replacement", "B.", "module.b", TtsPlaybackPlacement.CANCEL_SENTENCE_AND_PLAY)).accepted());

            assertEquals(0, engine.interruptions.get());
        } finally {
            engine.releaseBackground.countDown();
            runtime.stop();
        }
    }

    @Test
    void queuedSpeechRunsBeforePureSynthesisContinuationAtSentenceBoundary() throws Exception {
        BlockingFirstSynthesisEngine engine = new BlockingFirstSynthesisEngine();
        TtsRuntime runtime = runtime(engine, Collections.synchronizedList(new ArrayList<>()));
        prepare(runtime);

        assertTrue(runtime.synthesize(
                request("background", "Task one. Task two.", "module.task",
                        TtsPlaybackPlacement.QUEUE_AFTER_SESSION),
                false,
                30_000L,
                (index, audio, last) -> { },
                () -> { },
                failure -> { }
        ).accepted());
        assertTrue(engine.firstStarted.await(2L, TimeUnit.SECONDS));
        TtsSpeechSessionKey speech = TtsSpeechSessionKey.of("module.a", 51L, 1, "speech");
        assertTrue(runtime.submitSpeech(speech, TtsTextInputMode.DOCUMENT, true,
                request("speech", "Speech.", "module.a", TtsPlaybackPlacement.QUEUE_AFTER_SESSION)).accepted());

        engine.releaseFirst.countDown();
        assertTrue(engine.awaitInvocations(3));
        assertEquals(List.of("Task one.", "Speech.", "Task two."), engine.invocationTexts());
    }

    private TtsRuntime runtime(TtsSynthesisEngine engine, List<TtsRequestStatusPayload> statuses) {
        return new TtsRuntime(new FakeGameEnvironment(), executorManager, engine, new FakeAudioBridge(),
                ignored -> { }, ignored -> { }, statuses::add);
    }

    private static TtsRequest request(
            String requestId,
            String text,
            String source,
            TtsPlaybackPlacement placement
    ) {
        return new TtsRequest(
                requestId,
                requestId,
                requestId,
                requestId,
                text,
                TtsRequestSource.of(source),
                switch (placement) {
                    case DROP_IF_BUSY -> TtsPlaybackPolicy.DROP_IF_BUSY;
                    case QUEUE_AFTER_SESSION -> TtsPlaybackPolicy.QUEUE;
                    case INSERT_AFTER_SESSION -> TtsPlaybackPolicy.INSERT_AFTER_SESSION;
                    case INSERT_AFTER_SENTENCE -> TtsPlaybackPolicy.INSERT_AFTER_SENTENCE;
                    case CANCEL_SENTENCE_AND_PLAY -> TtsPlaybackPolicy.CANCEL_SENTENCE_AND_PLAY;
                    case CANCEL_SESSION_AND_PLAY -> TtsPlaybackPolicy.CANCEL_SESSION_AND_PLAY;
                },
                Priority.NORMAL,
                TtsVoiceProfile.defaults()
        );
    }

    private static void prepare(TtsRuntime runtime) throws InterruptedException {
        CountDownLatch prepared = new CountDownLatch(1);
        assertTrue(runtime.prepare(ignored -> prepared.countDown()).accepted());
        assertTrue(prepared.await(2L, TimeUnit.SECONDS));
    }

    private static final class BlockingFirstSynthesisEngine implements TtsSynthesisEngine {
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final List<String> invocations = Collections.synchronizedList(new ArrayList<>());

        @Override public boolean initialize() { return true; }
        @Override public boolean isInitialized() { return true; }
        @Override public boolean isAutoregressive() { return false; }
        @Override public int sampleRate() { return 24_000; }
        @Override public TtsBackendSnapshot backendSnapshot() { return TtsBackendSnapshot.unavailable(); }
        @Override public boolean useModel(String modelName) { return true; }

        @Override
        public void synthesize(TtsRequest request, TtsAudioSink sink) {
            invocations.add(request.text());
            if (invocations.size() == 1) {
                firstStarted.countDown();
                try {
                    releaseFirst.await(2L, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            sink.accept(new byte[]{1, 2});
        }

        @Override public void interrupt() { releaseFirst.countDown(); }
        @Override public void shutdown() { }

        private boolean awaitInvocations(int count) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 2_000L;
            while (System.currentTimeMillis() < deadline) {
                if (invocations.size() >= count) {
                    return true;
                }
                Thread.sleep(10L);
            }
            return invocations.size() >= count;
        }

        private List<String> invocationTexts() {
            synchronized (invocations) {
                return List.copyOf(invocations);
            }
        }
    }

    private static final class BackgroundBlockingSynthesisEngine implements TtsSynthesisEngine {
        private final CountDownLatch backgroundStarted = new CountDownLatch(1);
        private final CountDownLatch releaseBackground = new CountDownLatch(1);
        private final AtomicInteger interruptions = new AtomicInteger();

        @Override public boolean initialize() { return true; }
        @Override public boolean isInitialized() { return true; }
        @Override public boolean isAutoregressive() { return false; }
        @Override public int sampleRate() { return 24_000; }
        @Override public TtsBackendSnapshot backendSnapshot() { return TtsBackendSnapshot.unavailable(); }
        @Override public boolean useModel(String modelName) { return true; }

        @Override
        public void synthesize(TtsRequest request, TtsAudioSink sink) {
            if (request.requestId().equals("background")) {
                backgroundStarted.countDown();
                try {
                    releaseBackground.await(3L, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            sink.accept(new byte[]{1});
        }

        @Override
        public void interrupt() {
            interruptions.incrementAndGet();
            releaseBackground.countDown();
        }

        @Override public void shutdown() { }
    }

    private static final class FakeAudioBridge implements IAudioBridge {
        private Runnable callback;
        @Override public void ensureHardwareRunning() { }
        @Override public void releaseCaptureHardware() { }
        @Override public void startRecording() { }
        @Override public byte[] stopRecording() { return new byte[0]; }
        @Override public void startStreamRecording(java.util.function.Consumer<byte[]> onAudioChunk) { }
        @Override public void stopStreamRecording() { }
        @Override public void startTtsPlayback(int sampleRate) { }
        @Override public void feedTtsAudio(byte[] audio) { }
        @Override public void finishTtsPlayback() { Runnable current = callback; callback = null; if (current != null) current.run(); }
        @Override public void setOnPlaybackFinished(Runnable callback) { this.callback = callback; }
        @Override public void stopTtsPlayback() { callback = null; }
        @Override public void playAudio(byte[] audioData, int sampleRate) { }
        @Override public void stopPlayback() { }
        @Override public boolean isRecording() { return false; }
        @Override public boolean isPlaying() { return false; }
        @Override public boolean isStreaming() { return false; }
        @Override public List<String> getAvailableMicNames() { return List.of(); }
        @Override public String getCurrentMicName() { return ""; }
        @Override public void selectMic(String micName) { }
        @Override public void switchToNextMic() { }
        @Override public void shutdown() { }
    }

    private static final class FakeGameEnvironment implements IGameEnvironment {
        @Override public void displayMessageToPlayer(String message) { }
        @Override public void executeOnMainThread(Runnable task) { task.run(); }
        @Override public Path getGameDirectory() { return Path.of("."); }
        @Override public boolean isClientSide() { return true; }
        @Override public void openFolder(Path dir) { }
        @Override public void info(String msg) { }
        @Override public void warn(String msg) { }
        @Override public void error(String msg, Throwable t) { }
        @Override public com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink diagnostics() { return com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink.NOOP; }
    }
}
