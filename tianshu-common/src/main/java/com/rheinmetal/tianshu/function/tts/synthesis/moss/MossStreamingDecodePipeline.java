package com.rheinmetal.tianshu.function.tts.synthesis.moss;

import com.rheinmetal.tianshu.function.tts.synthesis.TtsCodecExecution;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class MossStreamingDecodePipeline {
    private static final int FRAMES_PER_BATCH = 4;
    private static final long QUEUE_POLL_MILLIS = 10L;

    private final int queueCapacity;
    private final TtsCodecExecution codecExecution;

    MossStreamingDecodePipeline(int queueCapacity, TtsCodecExecution codecExecution) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.queueCapacity = queueCapacity;
        this.codecExecution = Objects.requireNonNull(codecExecution, "codecExecution");
    }

    MossFrameGenerationResult run(
            FrameGeneration generation,
            DecoderFactory decoderFactory,
            Consumer<float[][]> audioConsumer,
            BooleanSupplier cancellationRequested
    ) throws Exception {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(decoderFactory, "decoderFactory");
        Consumer<float[][]> output = audioConsumer == null ? ignored -> { } : audioConsumer;
        BooleanSupplier externalCancellation = cancellationRequested == null ? () -> false : cancellationRequested;
        ArrayBlockingQueue<FrameBatch> queue = new ArrayBlockingQueue<>(queueCapacity);
        AtomicReference<Throwable> codecFailure = new AtomicReference<>();
        AtomicBoolean stopRequested = new AtomicBoolean();

        TtsCodecExecution.Task codecTask = codecExecution.submit(
                () -> consume(queue, decoderFactory, output, stopRequested, codecFailure)
        );
        if (!codecTask.accepted()) {
            throw new IllegalStateException("TTS_MOSS_CODEC_EXECUTION_REJECTED");
        }

        List<List<Integer>> pendingFrames = new ArrayList<>(FRAMES_PER_BATCH);
        MossFrameGenerationResult result;
        try {
            result = generation.generate((stepIndex, frame) -> {
                throwCodecFailure(codecFailure.get());
                if (externalCancellation.getAsBoolean() || stopRequested.get()) {
                    throw new CancellationException("TTS_MOSS_STREAMING_CANCELLED");
                }
                pendingFrames.add(List.copyOf(frame));
                if (pendingFrames.size() == FRAMES_PER_BATCH) {
                    if (offer(queue, new FrameBatch(pendingFrames, false), externalCancellation, stopRequested, codecFailure)) {
                        pendingFrames.clear();
                    }
                }
            }, () -> externalCancellation.getAsBoolean() || stopRequested.get());
        } catch (CancellationException cancellation) {
            stopRequested.set(true);
            codecTask.cancel("TTS_MOSS_STREAMING_CANCELLED");
            awaitCancelled(codecTask);
            throwCodecFailure(codecFailure.get());
            return MossFrameGenerationResult.cancelled(List.of(), 0);
        } catch (Throwable failure) {
            stopRequested.set(true);
            codecTask.cancel("TTS_MOSS_GENERATION_FAILED");
            awaitCancelled(codecTask);
            rethrow(failure);
            throw new IllegalStateException("unreachable");
        }

        throwCodecFailure(codecFailure.get());
        if (!result.naturallyEnded()) {
            stopRequested.set(true);
            codecTask.cancel(result.cancelled()
                    ? "TTS_MOSS_GENERATION_CANCELLED"
                    : "TTS_MOSS_GENERATION_LIMIT_REACHED");
            awaitCancelled(codecTask);
            throwCodecFailure(codecFailure.get());
            return result;
        }

        if (!offer(queue, new FrameBatch(pendingFrames, true), externalCancellation, stopRequested, codecFailure)) {
            stopRequested.set(true);
            codecTask.cancel("TTS_MOSS_STREAMING_CANCELLED");
            awaitCancelled(codecTask);
            return MossFrameGenerationResult.cancelled(result.frames(), result.maxFrameCount());
        }
        try {
            codecTask.await(externalCancellation);
        } catch (CancellationException cancellation) {
            stopRequested.set(true);
            codecTask.cancel("TTS_MOSS_STREAMING_CANCELLED");
            awaitCancelled(codecTask);
            throwCodecFailure(codecFailure.get());
            return MossFrameGenerationResult.cancelled(result.frames(), result.maxFrameCount());
        }
        throwCodecFailure(codecFailure.get());
        return result;
    }

    private static void consume(
            ArrayBlockingQueue<FrameBatch> queue,
            DecoderFactory decoderFactory,
            Consumer<float[][]> audioConsumer,
            AtomicBoolean stopRequested,
            AtomicReference<Throwable> codecFailure
    ) {
        try (Decoder decoder = decoderFactory.open()) {
            while (!stopRequested.get()) {
                FrameBatch batch = queue.poll(QUEUE_POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (batch == null) {
                    continue;
                }
                for (float[][] audio : decoder.decode(batch.frames(), batch.finalBatch())) {
                    if (audio != null && audio.length > 0 && audio[0].length > 0 && !stopRequested.get()) {
                        audioConsumer.accept(audio);
                    }
                }
                if (batch.finalBatch()) {
                    return;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (!stopRequested.get()) {
                codecFailure.compareAndSet(null, interrupted);
            }
        } catch (Throwable failure) {
            codecFailure.compareAndSet(null, failure);
            stopRequested.set(true);
        }
    }

    private static boolean offer(
            ArrayBlockingQueue<FrameBatch> queue,
            FrameBatch batch,
            BooleanSupplier cancellationRequested,
            AtomicBoolean stopRequested,
            AtomicReference<Throwable> codecFailure
    ) throws Exception {
        while (true) {
            throwCodecFailure(codecFailure.get());
            if (cancellationRequested.getAsBoolean() || stopRequested.get()) {
                return false;
            }
            try {
                if (queue.offer(batch, QUEUE_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    return true;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }
    }

    private static void awaitCancelled(TtsCodecExecution.Task task) {
        try {
            task.await(() -> false);
        } catch (Exception | LinkageError ignored) {
        }
    }

    private static void throwCodecFailure(Throwable failure) throws Exception {
        if (failure != null) {
            rethrow(failure);
        }
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException(failure);
    }

    @FunctionalInterface
    interface FrameGeneration {
        MossFrameGenerationResult generate(
                MossFrameGenerator.FrameCallback frameConsumer,
                BooleanSupplier cancellationRequested
        ) throws Exception;
    }

    @FunctionalInterface
    interface DecoderFactory {
        Decoder open() throws Exception;
    }

    interface Decoder extends AutoCloseable {
        List<float[][]> decode(List<List<Integer>> frames, boolean finalBatch) throws Exception;

        @Override
        void close() throws Exception;
    }

    private record FrameBatch(List<List<Integer>> frames, boolean finalBatch) {
        private FrameBatch {
            List<List<Integer>> copy = new ArrayList<>(frames == null ? 0 : frames.size());
            if (frames != null) {
                for (List<Integer> frame : frames) {
                    copy.add(List.copyOf(frame));
                }
            }
            frames = List.copyOf(copy);
        }
    }
}
