package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.function.tts.synthesis.TtsAudioSink;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsAudioDelivery;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisEngine;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisMetrics;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisMode;
import com.rheinmetal.tianshu.function.tts.text.TtsStreamBuffer;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;
import com.rheinmetal.tianshu.text.SentenceSegmenter;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class TtsSynthesisTaskCoordinator {
    private static final int DEFAULT_HANDOFF_CAPACITY = 2;
    private static final int PENDING_HANDOFF_CAPACITY = 8;

    private final TtsSynthesisEngine synthesisEngine;
    private final TtsSynthesisScheduler scheduler;
    private final TtsAdaptiveSynthesisPolicy synthesisPolicy;
    private final Consumer<TtsFailure> failureObserver;
    private final int handoffCapacity;
    private final Map<String, SynthesisTask> tasks = new ConcurrentHashMap<>();
    private final Object handoffLock = new Object();
    private final ArrayDeque<SynthesisTask> pendingAcknowledged = new ArrayDeque<>();
    private final ArrayDeque<SynthesisTask> awaitingAcknowledgement = new ArrayDeque<>();
    private SynthesisTask activeAcknowledged;

    TtsSynthesisTaskCoordinator(
            TtsSynthesisEngine synthesisEngine,
            TtsSynthesisScheduler scheduler,
            TtsAdaptiveSynthesisPolicy synthesisPolicy,
            Consumer<TtsFailure> failureObserver
    ) {
        this(synthesisEngine, scheduler, synthesisPolicy, failureObserver, DEFAULT_HANDOFF_CAPACITY);
    }

    TtsSynthesisTaskCoordinator(
            TtsSynthesisEngine synthesisEngine,
            TtsSynthesisScheduler scheduler,
            TtsAdaptiveSynthesisPolicy synthesisPolicy,
            Consumer<TtsFailure> failureObserver,
            int handoffCapacity
    ) {
        this.synthesisEngine = synthesisEngine;
        this.scheduler = scheduler;
        this.synthesisPolicy = synthesisPolicy;
        this.failureObserver = failureObserver == null ? ignored -> { } : failureObserver;
        this.handoffCapacity = Math.max(1, handoffCapacity);
    }

    TtsOperationResult submit(
            TtsRequest request,
            long ttlMillis,
            Consumer<byte[]> onAudio,
            Runnable onStarted,
            Runnable onComplete,
            Consumer<TtsFailure> onFailure
    ) {
        return submitInternal(request, ttlMillis, "", onAudio, onStarted, onComplete, onFailure);
    }

    TtsOperationResult submitAcknowledged(
            TtsRequest request,
            long ttlMillis,
            String ownerId,
            Consumer<byte[]> onAudio,
            Runnable onStarted,
            Runnable onComplete,
            Consumer<TtsFailure> onFailure
    ) {
        if (ownerId == null || ownerId.isBlank()) {
            return reject(onFailure, TtsFailureCode.INVALID_REQUEST, "TTS_AUDIO_OWNER_REQUIRED");
        }
        return submitInternal(request, ttlMillis, ownerId.trim(), onAudio, onStarted, onComplete, onFailure);
    }

    TtsOperationResult acknowledge(String ownerId, String requestId) {
        if (ownerId == null || ownerId.isBlank() || requestId == null || requestId.isBlank()) {
            return TtsOperationResult.rejected(TtsFailure.of(
                    TtsFailureCode.INVALID_REQUEST,
                    "TTS_AUDIO_ACK_INVALID"
            ));
        }
        SynthesisTask task = tasks.get(requestId.trim());
        if (task == null) {
            return TtsOperationResult.rejected(TtsFailure.of(
                    TtsFailureCode.REQUEST_NOT_FOUND,
                    "TTS_AUDIO_ACK_REQUEST_NOT_ACTIVE"
            ));
        }
        synchronized (handoffLock) {
            if (!awaitingAcknowledgement.contains(task) || !task.consumeAcknowledgement(ownerId.trim())) {
                return TtsOperationResult.rejected(TtsFailure.of(
                        TtsFailureCode.INVALID_REQUEST,
                        "TTS_AUDIO_ACK_OWNER_MISMATCH"
                ));
            }
            awaitingAcknowledgement.remove(task);
        }
        task.notifyCompletion();
        tasks.remove(task.request.requestId(), task);
        scheduleNextAcknowledged();
        return TtsOperationResult.accepted(requestId.trim());
    }

    private TtsOperationResult submitInternal(
            TtsRequest request,
            long ttlMillis,
            String ownerId,
            Consumer<byte[]> onAudio,
            Runnable onStarted,
            Runnable onComplete,
            Consumer<TtsFailure> onFailure
    ) {
        SynthesisTask task = new SynthesisTask(
                request,
                System.currentTimeMillis() + Math.max(1_000L, ttlMillis),
                splitSentences(request.text()),
                ownerId,
                onAudio,
                onStarted,
                onComplete,
                onFailure
        );
        if (tasks.putIfAbsent(request.requestId(), task) != null) {
            return reject(onFailure, TtsFailureCode.INVALID_REQUEST, "TTS_SYNTHESIS_REQUEST_ACTIVE");
        }
        ProtocolTaskHandle timeoutHandle = scheduler.scheduleTimeout(
                request,
                Duration.ofMillis(Math.max(1_000L, ttlMillis)),
                () -> expire(task)
        );
        if (timeoutHandle.state() == ProtocolTaskState.REJECTED) {
            tasks.remove(request.requestId(), task);
            return failRejected(task, TtsFailureCode.QUEUE_FULL, "TTS_SYNTHESIS_TIMEOUT_QUEUE_FULL");
        }
        task.armTimeout(timeoutHandle);
        if (task.requiresAcknowledgement()) {
            if (!enqueueAcknowledged(task)) {
                tasks.remove(request.requestId(), task);
                task.cancelTimeout();
                return failRejected(task, TtsFailureCode.QUEUE_FULL, "TTS_SYNTHESIS_HANDOFF_QUEUE_FULL");
            }
            return TtsOperationResult.accepted(request.requestId());
        }
        if (!schedule(task)) {
            tasks.remove(request.requestId(), task);
            return failRejected(task, TtsFailureCode.QUEUE_FULL, "TTS_SYNTHESIS_QUEUE_FULL");
        }
        return TtsOperationResult.accepted(request.requestId());
    }

    private boolean enqueueAcknowledged(SynthesisTask task) {
        boolean startNow;
        synchronized (handoffLock) {
            startNow = activeAcknowledged == null && awaitingAcknowledgement.size() < handoffCapacity;
            if (startNow) {
                activeAcknowledged = task;
            } else {
                if (pendingAcknowledged.size() >= PENDING_HANDOFF_CAPACITY) {
                    return false;
                }
                pendingAcknowledged.addLast(task);
            }
        }
        if (startNow && !schedule(task)) {
            synchronized (handoffLock) {
                if (activeAcknowledged == task) {
                    activeAcknowledged = null;
                }
            }
            return false;
        }
        return true;
    }

    private boolean schedule(SynthesisTask task) {
        ProtocolTaskHandle handle = scheduler.submit(task.request, task, () -> runNext(task));
        return handle.state() != ProtocolTaskState.REJECTED;
    }

    private void runNext(SynthesisTask task) {
        try {
            if (task.cancelled() || task.finished()) {
                return;
            }
            if (task.expired()) {
                expire(task);
                return;
            }
            task.start();
            if (!synthesisEngine.initialize()) {
                failTask(task, TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE,
                        "TTS_SYNTHESIS_ENGINE_UNAVAILABLE", false);
                return;
            }
            List<String> available = task.availableText();
            if (available.isEmpty()) {
                finish(task);
                return;
            }
            int contextualLimit = Math.max(1, synthesisEngine.contextualSentenceLimit(available));
            TtsSynthesisDecision decision = synthesisPolicy.planSynthesis(
                    available,
                    contextualLimit
            );
            String textGroup = task.takeTextGroup(decision.sentenceCount());
            if (textGroup == null) {
                finish(task);
                return;
            }
            TtsRequest groupRequest = withText(task.request, textGroup);
            synthesisEngine.synthesize(groupRequest, new CoordinatorAudioSink(decision.mode(), task::acceptAudio));
            if (task.cancelled() || task.finished()) {
                return;
            }
            if (task.hasRemainingText()) {
                scheduleContinuation(task);
            } else {
                finish(task);
            }
        } catch (Throwable throwable) {
            TtsFailure failure = TtsRuntimeFailurePolicy.classify(TtsFailureCode.SYNTHESIS_FAILED, throwable);
            failTask(task, failure, false);
        }
    }

    private void scheduleContinuation(SynthesisTask task) {
        if (!schedule(task)) {
            failTask(task, TtsFailureCode.QUEUE_FULL, "TTS_SYNTHESIS_CONTINUATION_QUEUE_FULL", false);
        }
    }

    private void finish(SynthesisTask task) {
        if (task.cancelled() || task.finished()) {
            removeTaskState(task);
            return;
        }
        byte[] audio = task.mergedAudio();
        if (audio.length == 0) {
            failTask(task, TtsFailureCode.SYNTHESIS_FAILED, "TTS_SYNTHESIS_EMPTY_AUDIO", false);
            return;
        }
        if (!task.requiresAcknowledgement()) {
            task.deliver(audio);
            task.complete();
            tasks.remove(task.request.requestId(), task);
            return;
        }
        synchronized (handoffLock) {
            if (activeAcknowledged != task || !task.prepareAcknowledgement(audio)) {
                return;
            }
            activeAcknowledged = null;
            awaitingAcknowledgement.addLast(task);
        }
        task.deliverRetainedAudio();
        scheduleNextAcknowledged();
    }

    private void scheduleNextAcknowledged() {
        SynthesisTask next;
        synchronized (handoffLock) {
            if (activeAcknowledged != null || awaitingAcknowledgement.size() >= handoffCapacity) {
                return;
            }
            do {
                next = pendingAcknowledged.pollFirst();
            } while (next != null && (next.cancelled() || next.finished()));
            if (next == null) {
                return;
            }
            activeAcknowledged = next;
        }
        if (!schedule(next)) {
            failTask(next, TtsFailureCode.QUEUE_FULL, "TTS_SYNTHESIS_QUEUE_FULL", false);
        }
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
            if (cancelTask(task, cancelReason(reason), true)) {
                count++;
            }
        }
        return count;
    }

    int cancelAll(String reason) {
        List<SynthesisTask> currentTasks = List.copyOf(tasks.values());
        synchronized (handoffLock) {
            pendingAcknowledged.clear();
            awaitingAcknowledgement.clear();
            activeAcknowledged = null;
        }
        tasks.clear();
        int count = 0;
        for (SynthesisTask task : currentTasks) {
            if (task.cancel(cancelReason(reason))) {
                count++;
            }
            scheduler.interrupt(task);
        }
        return count;
    }

    private void expire(SynthesisTask task) {
        if (task != null && task.expire()) {
            tasks.remove(task.request.requestId(), task);
            removeTaskState(task);
            scheduler.interrupt(task);
            scheduleNextAcknowledged();
        }
    }

    private boolean cancelTask(SynthesisTask task, String reason, boolean interrupt) {
        if (task == null || !task.cancel(reason)) {
            return false;
        }
        tasks.remove(task.request.requestId(), task);
        removeTaskState(task);
        if (interrupt) {
            scheduler.interrupt(task);
        }
        scheduleNextAcknowledged();
        return true;
    }

    private void failTask(SynthesisTask task, TtsFailureCode code, String detail, boolean interrupt) {
        failTask(task, TtsFailure.of(code, detail), interrupt);
    }

    private void failTask(SynthesisTask task, TtsFailure failure, boolean interrupt) {
        if (task == null || !task.fail(failure)) {
            return;
        }
        failureObserver.accept(failure);
        tasks.remove(task.request.requestId(), task);
        removeTaskState(task);
        if (interrupt) {
            scheduler.interrupt(task);
        }
        scheduleNextAcknowledged();
    }

    private void removeTaskState(SynthesisTask task) {
        synchronized (handoffLock) {
            pendingAcknowledged.remove(task);
            awaitingAcknowledgement.remove(task);
            if (activeAcknowledged == task) {
                activeAcknowledged = null;
            }
        }
    }

    private TtsOperationResult failRejected(SynthesisTask task, TtsFailureCode code, String detail) {
        TtsFailure failure = TtsFailure.of(code, detail);
        failureObserver.accept(failure);
        task.fail(failure);
        return TtsOperationResult.rejected(failure);
    }

    private static TtsOperationResult reject(
            Consumer<TtsFailure> onFailure,
            TtsFailureCode code,
            String detail
    ) {
        TtsFailure failure = TtsFailure.of(code, detail);
        if (onFailure != null) {
            onFailure.accept(failure);
        }
        return TtsOperationResult.rejected(failure);
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
        return reason == null || reason.isBlank() ? "TTS_SYNTHESIS_CANCELLED" : reason;
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
        public TtsAudioDelivery delivery() {
            return TtsAudioDelivery.COMPLETE_RESULT;
        }

        @Override
        public void reportSynthesisMetrics(TtsSynthesisMetrics metrics) {
            synthesisPolicy.record(metrics);
        }
    }

    private static final class SynthesisTask {
        private final TtsRequest request;
        private final long expireAtMillis;
        private final ArrayDeque<String> sentences;
        private final String ownerId;
        private final Consumer<byte[]> onAudio;
        private final Runnable onStarted;
        private final Runnable onComplete;
        private final Consumer<TtsFailure> onFailure;
        private final List<byte[]> audioChunks = new ArrayList<>();
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<ProtocolTaskHandle> timeoutHandle = new AtomicReference<>();
        private boolean awaitingAcknowledgement;
        private byte[] retainedAudio;

        private SynthesisTask(
                TtsRequest request,
                long expireAtMillis,
                List<String> sentences,
                String ownerId,
                Consumer<byte[]> onAudio,
                Runnable onStarted,
                Runnable onComplete,
                Consumer<TtsFailure> onFailure
        ) {
            this.request = request;
            this.expireAtMillis = expireAtMillis;
            this.sentences = new ArrayDeque<>(sentences == null ? List.of() : sentences);
            this.ownerId = ownerId == null ? "" : ownerId;
            this.onAudio = onAudio;
            this.onStarted = onStarted;
            this.onComplete = onComplete;
            this.onFailure = onFailure;
        }

        private boolean requiresAcknowledgement() {
            return !ownerId.isBlank();
        }

        private boolean expired() {
            return System.currentTimeMillis() > expireAtMillis;
        }

        private boolean cancelled() {
            return cancelled.get();
        }

        private boolean finished() {
            return finished.get();
        }

        private void start() {
            if (!finished() && !cancelled() && started.compareAndSet(false, true) && onStarted != null) {
                onStarted.run();
            }
        }

        private synchronized List<String> availableText() {
            return List.copyOf(sentences);
        }

        private synchronized String takeTextGroup(int sentenceCount) {
            int limit = Math.max(1, sentenceCount);
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < limit && !sentences.isEmpty(); index++) {
                text.append(sentences.removeFirst());
            }
            return text.isEmpty() ? null : text.toString();
        }

        private synchronized boolean hasRemainingText() {
            return !sentences.isEmpty();
        }

        private synchronized void acceptAudio(byte[] audio) {
            if (!cancelled() && !finished() && audio != null && audio.length > 0) {
                audioChunks.add(audio);
            }
        }

        private synchronized byte[] mergedAudio() {
            return merge(audioChunks);
        }

        private void deliver(byte[] audio) {
            if (!cancelled() && !finished() && onAudio != null) {
                onAudio.accept(audio);
            }
        }

        private synchronized boolean prepareAcknowledgement(byte[] audio) {
            if (cancelled() || finished() || awaitingAcknowledgement) {
                return false;
            }
            retainedAudio = audio;
            awaitingAcknowledgement = true;
            audioChunks.clear();
            return true;
        }

        private boolean deliverRetainedAudio() {
            byte[] audio;
            synchronized (this) {
                if (!awaitingAcknowledgement || finished() || cancelled()) {
                    return false;
                }
                audio = retainedAudio;
            }
            deliver(audio);
            return true;
        }

        private boolean consumeAcknowledgement(String acknowledgingOwnerId) {
            synchronized (this) {
                if (!awaitingAcknowledgement
                        || cancelled()
                        || !ownerId.equals(acknowledgingOwnerId)
                        || !finished.compareAndSet(false, true)) {
                    return false;
                }
                awaitingAcknowledgement = false;
                retainedAudio = null;
                audioChunks.clear();
            }
            cancelTimeout();
            return true;
        }

        private boolean cancel(String reason) {
            return failTerminal(TtsFailure.of(TtsFailureCode.CANCELLED, reason), true, false);
        }

        private boolean expire() {
            return failTerminal(TtsFailure.of(TtsFailureCode.EXPIRED, "TTS_SYNTHESIS_EXPIRED"), true, true);
        }

        private void complete() {
            if (finished.compareAndSet(false, true)) {
                cancelTimeout();
                clearAudio();
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        }

        private boolean fail(TtsFailure failure) {
            return failTerminal(failure, false, false);
        }

        private boolean failTerminal(TtsFailure failure, boolean markCancelled, boolean timeoutTriggered) {
            if (!finished.compareAndSet(false, true)) {
                return false;
            }
            if (markCancelled) {
                cancelled.set(true);
            }
            if (timeoutTriggered) {
                timeoutHandle.set(null);
            } else {
                cancelTimeout();
            }
            clearAudio();
            if (onFailure != null) {
                onFailure.accept(failure == null ? TtsFailure.of(TtsFailureCode.UNKNOWN, "") : failure);
            }
            return true;
        }

        private void notifyCompletion() {
            if (onComplete != null) {
                onComplete.run();
            }
        }

        private synchronized void clearAudio() {
            audioChunks.clear();
            retainedAudio = null;
            awaitingAcknowledgement = false;
        }

        private void armTimeout(ProtocolTaskHandle handle) {
            if (!timeoutHandle.compareAndSet(null, handle) || finished()) {
                handle.cancel("TTS_SYNTHESIS_ALREADY_FINISHED");
                timeoutHandle.compareAndSet(handle, null);
            }
        }

        private void cancelTimeout() {
            ProtocolTaskHandle handle = timeoutHandle.getAndSet(null);
            if (handle != null && !handle.isDone()) {
                handle.cancel("TTS_SYNTHESIS_FINISHED");
            }
        }
    }
}
