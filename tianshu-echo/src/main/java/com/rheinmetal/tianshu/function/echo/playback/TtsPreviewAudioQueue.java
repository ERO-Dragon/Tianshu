package com.rheinmetal.tianshu.function.echo.playback;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class TtsPreviewAudioQueue {
    private final TtsPreviewPlaybackPolicy policy;
    private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
    private long bufferedBytes;
    private boolean finished;
    private boolean cancelled;

    public TtsPreviewAudioQueue(TtsPreviewPlaybackPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public synchronized OfferResult offer(byte[] audio, Duration timeout, BooleanSupplier cancellation) {
        if (audio == null || audio.length == 0) {
            return OfferResult.REJECTED;
        }
        BooleanSupplier isCancelled = cancellation == null ? () -> false : cancellation;
        long timeoutNanos = timeout == null ? 0L : Math.max(0L, timeout.toNanos());
        long deadline = System.nanoTime() + timeoutNanos;
        while (!terminal() && !isCancelled.getAsBoolean()
                && policy.bytesToMillis((int) Math.min(Integer.MAX_VALUE, bufferedBytes + audio.length)) > policy.maxQueueMillis()) {
            if (timeoutNanos == 0L) {
                return OfferResult.REJECTED;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                return OfferResult.REJECTED;
            }
            try {
                long millis = Math.max(1L, remaining / 1_000_000L);
                int nanos = (int) Math.min(999_999L, remaining % 1_000_000L);
                wait(millis, nanos);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return OfferResult.CANCELLED;
            }
        }
        if (cancelled || isCancelled.getAsBoolean()) {
            return OfferResult.CANCELLED;
        }
        if (finished || policy.bytesToMillis((int) Math.min(Integer.MAX_VALUE, bufferedBytes + audio.length)) > policy.maxQueueMillis()) {
            return OfferResult.REJECTED;
        }
        chunks.addLast(audio);
        bufferedBytes += audio.length;
        notifyAll();
        return OfferResult.ACCEPTED;
    }

    public synchronized Poll poll() {
        byte[] audio = chunks.pollFirst();
        if (audio != null) {
            bufferedBytes -= audio.length;
            notifyAll();
            return new Poll(PollResult.AUDIO, audio);
        }
        return new Poll(finished ? PollResult.FINISHED : PollResult.EMPTY, null);
    }

    public synchronized long bufferedMillis() {
        return policy.bytesToMillis((int) Math.min(Integer.MAX_VALUE, bufferedBytes));
    }

    public long audioDurationMillis(byte[] audio) {
        return audio == null ? 0L : policy.bytesToMillis(audio.length);
    }

    public synchronized boolean canStart() {
        return policy.shouldStart(bufferedMillis(), finished);
    }

    public synchronized void finish() {
        finished = true;
        notifyAll();
    }

    public synchronized void cancel() {
        cancelled = true;
        chunks.clear();
        bufferedBytes = 0L;
        notifyAll();
    }

    private boolean terminal() {
        return finished || cancelled;
    }

    public enum OfferResult { ACCEPTED, REJECTED, CANCELLED }

    public enum PollResult { AUDIO, EMPTY, FINISHED }

    public record Poll(PollResult result, byte[] audio) {
    }
}
