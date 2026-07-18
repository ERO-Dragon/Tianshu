package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.function.tts.synthesis.TtsAudioSink;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisEngine;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisMetrics;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisMode;
import com.rheinmetal.tianshu.function.tts.text.TtsStreamBuffer;
import com.rheinmetal.tianshu.text.SentenceSegmenter;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;

import java.time.Duration;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class TtsSynthesisTaskCoordinator {
    private final TtsSynthesisEngine synthesisEngine;
    private final TtsSynthesisScheduler scheduler;
    private final TtsAdaptiveSynthesisPolicy synthesisPolicy;
    private final Consumer<TtsFailure> failureObserver;
    private final Map<String, SynthesisTask> tasks = new ConcurrentHashMap<>();

    TtsSynthesisTaskCoordinator(
            TtsSynthesisEngine synthesisEngine,
            TtsSynthesisScheduler scheduler,
            TtsAdaptiveSynthesisPolicy synthesisPolicy,
            Consumer<TtsFailure> failureObserver
    ) {
        this.synthesisEngine = synthesisEngine;
        this.scheduler = scheduler;
        this.synthesisPolicy = synthesisPolicy;
        this.failureObserver = failureObserver == null ? ignored -> { } : failureObserver;
    }

    TtsOperationResult submit(
            TtsRequest request,
            boolean streaming,
            long ttlMillis,
            TtsAudioChunkConsumer onAudio,
            Runnable onComplete,
            Consumer<TtsFailure> onFailure
    ) {
        SynthesisTask task = new SynthesisTask(
                request,
                streaming,
                System.currentTimeMillis() + Math.max(1_000L, ttlMillis),
                splitSentences(request.text()),
                onAudio,
                onComplete,
                onFailure
        );
        if (tasks.putIfAbsent(request.requestId(), task) != null) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.INVALID_REQUEST,
                    "TTS synthesis request id is already active: " + request.requestId());
            failureObserver.accept(failure);
            task.fail(failure);
            return TtsOperationResult.rejected(failure);
        }
        ProtocolTaskHandle timeoutHandle = scheduler.scheduleTimeout(
                request,
                Duration.ofMillis(Math.max(1_000L, ttlMillis)),
                () -> expire(task)
        );
        if (timeoutHandle.state() == ProtocolTaskState.REJECTED) {
            tasks.remove(request.requestId(), task);
            TtsFailure failure = TtsFailure.of(TtsFailureCode.QUEUE_FULL,
                    "TTS synthesis timeout queue is full");
            failureObserver.accept(failure);
            task.fail(failure);
            return TtsOperationResult.rejected(failure);
        }
        task.armTimeout(timeoutHandle);
        ProtocolTaskHandle handle = scheduler.submit(request, task, () -> runNext(task));
        if (handle.state() == ProtocolTaskState.REJECTED) {
            tasks.remove(request.requestId(), task);
            TtsFailure failure = TtsFailure.of(TtsFailureCode.QUEUE_FULL, "TTS synthesis queue is full");
            failureObserver.accept(failure);
            task.fail(failure);
            return TtsOperationResult.rejected(failure);
        }
        return TtsOperationResult.accepted(request.requestId());
    }

    int stopRequest(String requestId, String reason) {
        if (requestId == null || requestId.isBlank()) {
            return 0;
        }
        String normalized = requestId.trim();
        String groupPrefix = normalized.endsWith(":") ? normalized : normalized + ":";
        int count = 0;
        for (SynthesisTask task : List.copyOf(tasks.values())) {
            String taskRequestId = task.request.requestId();
            if (!taskRequestId.equals(normalized) && !taskRequestId.startsWith(groupPrefix)) {
                continue;
            }
            if (task.cancel(cancelReason(reason))) {
                count++;
            }
            tasks.remove(taskRequestId, task);
            scheduler.interrupt(task);
        }
        return count;
    }

    int cancelAll(String reason) {
        int count = 0;
        List<SynthesisTask> currentTasks = List.copyOf(tasks.values());
        for (SynthesisTask task : currentTasks) {
            if (task.cancel(cancelReason(reason))) {
                count++;
            }
        }
        tasks.clear();
        for (SynthesisTask task : currentTasks) {
            scheduler.interrupt(task);
        }
        return count;
    }

    private void runNext(SynthesisTask task) {
        try {
            if (task.cancelled()) {
                return;
            }
            if (task.expired()) {
                if (task.expire(false)) {
                    tasks.remove(task.request.requestId(), task);
                }
                return;
            }
            if (task.cancelled()) {
                return;
            }
            if (!synthesisEngine.initialize()) {
                TtsFailure failure = TtsFailure.of(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, "TTS synthesis engine is unavailable");
                failureObserver.accept(failure);
                task.fail(failure);
                return;
            }
            String sentence = task.nextSentence();
            if (sentence == null) {
                finish(task);
                return;
            }
            TtsRequest sentenceRequest = withText(task.request, sentence);
            if (task.streaming) {
                runStreaming(task, sentenceRequest);
            } else {
                runFull(task, sentenceRequest);
            }
            if (task.cancelled()) {
                tasks.remove(task.request.requestId(), task);
                return;
            }
            if (task.hasRemainingSentences()) {
                scheduleContinuation(task);
            } else {
                finish(task);
            }
        } catch (Throwable throwable) {
            TtsFailure failure = TtsRuntimeFailurePolicy.classify(TtsFailureCode.SYNTHESIS_FAILED, throwable);
            failureObserver.accept(failure);
            task.fail(failure);
            tasks.remove(task.request.requestId(), task);
        } finally {
            // Backend ownership is released by TtsSynthesisScheduler.
        }
    }

    private void runStreaming(SynthesisTask task, TtsRequest sentenceRequest) {
        synthesisEngine.synthesize(sentenceRequest, new CoordinatorAudioSink(TtsSynthesisMode.STREAMING, audio -> {
            if (!task.cancelled()) {
                task.acceptStreamingAudio(audio);
            }
        }));
    }

    private void runFull(SynthesisTask task, TtsRequest sentenceRequest) {
        synthesisEngine.synthesize(sentenceRequest, new CoordinatorAudioSink(TtsSynthesisMode.FULL, audio -> {
            if (!task.cancelled()) {
                task.acceptFullAudio(audio);
            }
        }));
    }

    private void scheduleContinuation(SynthesisTask task) {
        ProtocolTaskHandle handle = scheduler.submit(task.request, task, () -> runNext(task));
        if (handle.state() == ProtocolTaskState.REJECTED) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.QUEUE_FULL, "TTS synthesis continuation queue is full");
            failureObserver.accept(failure);
            task.fail(failure);
            tasks.remove(task.request.requestId(), task);
        }
    }

    private void finish(SynthesisTask task) {
        if (task.cancelled()) {
            tasks.remove(task.request.requestId(), task);
            return;
        }
        if (task.streaming) {
            task.acceptAudio(task.chunkIndex.get(), new byte[0], true);
        } else {
            task.acceptAudio(0, merge(task.fullChunks), true);
        }
        task.complete();
        tasks.remove(task.request.requestId(), task);
    }

    private void expire(SynthesisTask task) {
        if (task != null && task.expire(true)) {
            tasks.remove(task.request.requestId(), task);
            scheduler.interrupt(task);
        }
    }

    private static TtsRequest withText(TtsRequest request, String text) {
        return new TtsRequest(
                request.requestId(),
                request.groupId(),
                request.envelopeId(),
                request.traceId(),
                text,
                request.source(),
                request.playbackPolicy(),
                request.priority(),
                request.voiceProfile()
        );
    }

    private static List<String> splitSentences(String text) {
        TtsStreamBuffer buffer = new TtsStreamBuffer(new SentenceSegmenter());
        List<String> sentences = new ArrayList<>(buffer.appendSegments(text));
        buffer.flush().ifPresent(sentences::add);
        return sentences;
    }

    private static String cancelReason(String reason) {
        return reason == null || reason.isBlank() ? "synthesis task cancelled" : reason;
    }

    private static byte[] merge(List<byte[]> chunks) {
        int size = chunks.stream().filter(java.util.Objects::nonNull).mapToInt(chunk -> chunk.length).sum();
        byte[] merged = new byte[size];
        int offset = 0;
        for (byte[] chunk : chunks) {
            if (chunk == null || chunk.length == 0) {
                continue;
            }
            System.arraycopy(chunk, 0, merged, offset, chunk.length);
            offset += chunk.length;
        }
        return merged;
    }

    private final class CoordinatorAudioSink implements TtsAudioSink {
        private final TtsSynthesisMode mode;
        private final Consumer<byte[]> audioConsumer;

        private CoordinatorAudioSink(TtsSynthesisMode mode, Consumer<byte[]> audioConsumer) {
            this.mode = mode;
            this.audioConsumer = audioConsumer;
        }

        @Override
        public void accept(byte[] audio) {
            if (audio != null && audio.length > 0) {
                audioConsumer.accept(audio);
            }
        }

        @Override
        public TtsSynthesisMode preferredSynthesisMode() {
            return mode;
        }

        @Override
        public void reportSynthesisMetrics(TtsSynthesisMetrics metrics) {
            synthesisPolicy.record(metrics);
        }
    }

    private static final class SynthesisTask {
        private final TtsRequest request;
        private final boolean streaming;
        private final long expireAtMillis;
        private final ArrayDeque<String> sentences;
        private final List<byte[]> fullChunks = new ArrayList<>();
        private final AtomicInteger chunkIndex = new AtomicInteger();
        private final TtsAudioChunkConsumer onAudio;
        private final Runnable onComplete;
        private final Consumer<TtsFailure> onFailure;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<ProtocolTaskHandle> timeoutHandle = new AtomicReference<>();

        private SynthesisTask(TtsRequest request, boolean streaming, long expireAtMillis, List<String> sentences,
                              TtsAudioChunkConsumer onAudio,
                              Runnable onComplete, Consumer<TtsFailure> onFailure) {
            this.request = request;
            this.streaming = streaming;
            this.expireAtMillis = expireAtMillis;
            this.sentences = new ArrayDeque<>(sentences == null ? List.of() : sentences);
            this.onAudio = onAudio;
            this.onComplete = onComplete;
            this.onFailure = onFailure;
        }

        private boolean expired() {
            return System.currentTimeMillis() > expireAtMillis;
        }

        private boolean cancelled() {
            return cancelled.get();
        }

        private String nextSentence() {
            return sentences.pollFirst();
        }

        private boolean hasRemainingSentences() {
            return !sentences.isEmpty();
        }

        private boolean cancel(String reason) {
            cancelled.set(true);
            return fail(TtsFailure.of(TtsFailureCode.CANCELLED, reason));
        }

        private boolean expire(boolean timeoutTriggered) {
            cancelled.set(true);
            if (timeoutTriggered) {
                timeoutHandle.set(null);
            }
            return fail(TtsFailure.of(TtsFailureCode.EXPIRED,
                    "TTS synthesis task expired: " + request.requestId()));
        }

        private void armTimeout(ProtocolTaskHandle handle) {
            if (!timeoutHandle.compareAndSet(null, handle) || finished.get()) {
                handle.cancel("TTS synthesis task already finished");
                timeoutHandle.compareAndSet(handle, null);
            }
        }

        private void acceptAudio(int chunkIndex, byte[] audio, boolean last) {
            if (!cancelled() && onAudio != null) {
                onAudio.accept(chunkIndex, audio, last);
            }
        }

        private void acceptStreamingAudio(byte[] audio) {
            acceptAudio(chunkIndex.getAndIncrement(), audio, false);
        }

        private void acceptFullAudio(byte[] audio) {
            if (audio != null && audio.length > 0) {
                fullChunks.add(audio);
            }
        }

        private void complete() {
            if (finished.compareAndSet(false, true)) {
                cancelTimeout();
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        }

        private boolean fail(TtsFailure failure) {
            if (!finished.compareAndSet(false, true)) {
                return false;
            }
            cancelTimeout();
            if (onFailure != null) {
                onFailure.accept(failure == null ? TtsFailure.of(TtsFailureCode.UNKNOWN, "") : failure);
            }
            return true;
        }

        private void cancelTimeout() {
            ProtocolTaskHandle handle = timeoutHandle.getAndSet(null);
            if (handle != null && !handle.isDone()) {
                handle.cancel("TTS synthesis task finished");
            }
        }
    }
}
