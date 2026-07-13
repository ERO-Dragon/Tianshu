package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.function.tts.synthesis.TtsAudioSink;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisEngine;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisMetrics;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisMode;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;

import java.util.ArrayList;
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
    private final AtomicReference<SynthesisTask> active = new AtomicReference<>();
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
                onAudio,
                onComplete,
                onFailure
        );
        tasks.put(request.requestId(), task);
        ProtocolTaskHandle handle = scheduler.submit(request, () -> run(task));
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
            if (active.compareAndSet(task, null)) {
                synthesisEngine.interrupt();
            }
        }
        return count;
    }

    int cancelAll(String reason) {
        int count = 0;
        for (SynthesisTask task : List.copyOf(tasks.values())) {
            if (task.cancel(cancelReason(reason))) {
                count++;
            }
        }
        tasks.clear();
        SynthesisTask activeTask = active.getAndSet(null);
        if (activeTask != null) {
            synthesisEngine.interrupt();
        }
        return count;
    }

    boolean preemptActive(String reason) {
        SynthesisTask task = active.get();
        if (task == null || !task.cancel(cancelReason(reason))) {
            return false;
        }
        synthesisEngine.interrupt();
        return true;
    }

    private void run(SynthesisTask task) {
        try {
            if (task.cancelled()) {
                return;
            }
            if (task.expired()) {
                task.fail(TtsFailure.of(TtsFailureCode.EXPIRED, "TTS synthesis task expired: " + task.request.requestId()));
                return;
            }
            active.set(task);
            if (task.cancelled()) {
                return;
            }
            if (!synthesisEngine.initialize()) {
                TtsFailure failure = TtsFailure.of(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, "TTS synthesis engine is unavailable");
                failureObserver.accept(failure);
                task.fail(failure);
                return;
            }
            if (task.streaming) {
                runStreaming(task);
            } else {
                runFull(task);
            }
            if (!task.cancelled()) {
                task.complete();
            }
        } catch (Throwable throwable) {
            TtsFailure failure = TtsRuntimeFailurePolicy.classify(TtsFailureCode.SYNTHESIS_FAILED, throwable);
            failureObserver.accept(failure);
            task.fail(failure);
        } finally {
            active.compareAndSet(task, null);
            tasks.remove(task.request.requestId(), task);
        }
    }

    private void runStreaming(SynthesisTask task) {
        AtomicInteger chunkIndex = new AtomicInteger();
        synthesisEngine.synthesize(task.request, new CoordinatorAudioSink(TtsSynthesisMode.STREAMING, audio -> {
            if (!task.cancelled()) {
                task.acceptAudio(chunkIndex.getAndIncrement(), audio, false);
            }
        }));
        if (!task.cancelled()) {
            task.acceptAudio(chunkIndex.get(), new byte[0], true);
        }
    }

    private void runFull(SynthesisTask task) {
        List<byte[]> chunks = new ArrayList<>();
        synthesisEngine.synthesize(task.request, new CoordinatorAudioSink(TtsSynthesisMode.FULL, audio -> {
            if (!task.cancelled()) {
                chunks.add(audio);
            }
        }));
        if (!task.cancelled()) {
            task.acceptAudio(0, merge(chunks), true);
        }
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
        private final TtsAudioChunkConsumer onAudio;
        private final Runnable onComplete;
        private final Consumer<TtsFailure> onFailure;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        private SynthesisTask(TtsRequest request, boolean streaming, long expireAtMillis, TtsAudioChunkConsumer onAudio,
                              Runnable onComplete, Consumer<TtsFailure> onFailure) {
            this.request = request;
            this.streaming = streaming;
            this.expireAtMillis = expireAtMillis;
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

        private boolean cancel(String reason) {
            cancelled.set(true);
            return fail(TtsFailure.of(TtsFailureCode.CANCELLED, reason));
        }

        private void acceptAudio(int chunkIndex, byte[] audio, boolean last) {
            if (!cancelled() && onAudio != null) {
                onAudio.accept(chunkIndex, audio, last);
            }
        }

        private void complete() {
            if (finished.compareAndSet(false, true) && onComplete != null) {
                onComplete.run();
            }
        }

        private boolean fail(TtsFailure failure) {
            if (!finished.compareAndSet(false, true)) {
                return false;
            }
            if (onFailure != null) {
                onFailure.accept(failure == null ? TtsFailure.of(TtsFailureCode.UNKNOWN, "") : failure);
            }
            return true;
        }
    }
}
