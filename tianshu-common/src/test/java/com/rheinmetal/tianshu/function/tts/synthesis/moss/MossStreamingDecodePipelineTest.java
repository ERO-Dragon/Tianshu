package com.rheinmetal.tianshu.function.tts.synthesis.moss;

import com.rheinmetal.tianshu.function.tts.synthesis.TtsCodecExecution;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MossStreamingDecodePipelineTest {
    @Test
    void decodesFixedFourFrameBatchesAndFlushesNaturalTail() throws Exception {
        try (ThreadedCodecExecution execution = new ThreadedCodecExecution()) {
            RecordingDecoder decoder = new RecordingDecoder();
            MossStreamingDecodePipeline pipeline = new MossStreamingDecodePipeline(2, execution);

            MossFrameGenerationResult result = pipeline.run(
                    generator(10, MossFrameGenerationTermination.NATURAL_END, null),
                    () -> decoder,
                    ignored -> { },
                    () -> false
            );

            assertTrue(result.naturallyEnded());
            assertEquals(List.of(4, 4, 2), decoder.batchSizes);
            assertEquals(List.of(false, false, true), decoder.finalBatches);
            assertEquals(range(10), decoder.flattenedFrames);
        }
    }

    @Test
    void boundedQueueAppliesBackpressureWithoutDroppingFrames() throws Exception {
        try (ThreadedCodecExecution execution = new ThreadedCodecExecution()) {
            RecordingDecoder decoder = new RecordingDecoder(15L, -1);
            MossStreamingDecodePipeline pipeline = new MossStreamingDecodePipeline(1, execution);

            pipeline.run(
                    generator(20, MossFrameGenerationTermination.NATURAL_END, null),
                    () -> decoder,
                    ignored -> { },
                    () -> false
            );

            assertEquals(range(20), decoder.flattenedFrames);
            assertEquals(List.of(4, 4, 4, 4, 4, 0), decoder.batchSizes);
            assertTrue(decoder.finalBatches.get(decoder.finalBatches.size() - 1));
        }
    }

    @Test
    void frameLimitDoesNotFlushPendingTailAsSuccessfulAudio() throws Exception {
        try (ThreadedCodecExecution execution = new ThreadedCodecExecution()) {
            RecordingDecoder decoder = new RecordingDecoder();
            MossStreamingDecodePipeline pipeline = new MossStreamingDecodePipeline(2, execution);

            MossFrameGenerationResult result = pipeline.run(
                    generator(6, MossFrameGenerationTermination.FRAME_LIMIT_REACHED, null),
                    () -> decoder,
                    ignored -> { },
                    () -> false
            );

            assertEquals(MossFrameGenerationTermination.FRAME_LIMIT_REACHED, result.termination());
            assertFalse(decoder.finalBatches.stream().anyMatch(Boolean::booleanValue));
            assertTrue(decoder.flattenedFrames.size() <= 4);
        }
    }

    @Test
    void codecFailureStopsGenerationAndPropagates() throws Exception {
        try (ThreadedCodecExecution execution = new ThreadedCodecExecution()) {
            RecordingDecoder decoder = new RecordingDecoder(0L, 1);
            MossStreamingDecodePipeline pipeline = new MossStreamingDecodePipeline(1, execution);

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> pipeline.run(
                            generator(100, MossFrameGenerationTermination.NATURAL_END, null),
                            () -> decoder,
                            ignored -> { },
                            () -> false
                    )
            );

            assertEquals("codec failed", failure.getMessage());
        }
    }

    @Test
    void cancellationStopsWithoutNaturalTailFlush() throws Exception {
        try (ThreadedCodecExecution execution = new ThreadedCodecExecution()) {
            AtomicBoolean cancelled = new AtomicBoolean();
            RecordingDecoder decoder = new RecordingDecoder();
            MossStreamingDecodePipeline pipeline = new MossStreamingDecodePipeline(2, execution);

            MossFrameGenerationResult result = pipeline.run(
                    generator(20, MossFrameGenerationTermination.CANCELLED, cancelled),
                    () -> decoder,
                    ignored -> { },
                    cancelled::get
            );

            assertTrue(result.cancelled());
            assertFalse(decoder.finalBatches.stream().anyMatch(Boolean::booleanValue));
        }
    }

    private static MossStreamingDecodePipeline.FrameGeneration generator(
            int frameCount,
            MossFrameGenerationTermination termination,
            AtomicBoolean cancellation
    ) {
        return (frameConsumer, cancellationRequested) -> {
            List<List<Integer>> frames = new ArrayList<>();
            for (int index = 0; index < frameCount; index++) {
                if (cancellationRequested.getAsBoolean()) {
                    return MossFrameGenerationResult.cancelled(frames, frameCount);
                }
                List<Integer> frame = List.of(index);
                frames.add(frame);
                frameConsumer.onFrame(index, frame);
                if (cancellation != null && index == 5) {
                    cancellation.set(true);
                }
            }
            return switch (termination) {
                case NATURAL_END -> MossFrameGenerationResult.naturalEnd(frames, frameCount);
                case CANCELLED -> MossFrameGenerationResult.cancelled(frames, frameCount);
                case FRAME_LIMIT_REACHED -> MossFrameGenerationResult.frameLimitReached(frames, frameCount);
            };
        };
    }

    private static List<Integer> range(int size) {
        List<Integer> values = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            values.add(index);
        }
        return values;
    }

    private static final class RecordingDecoder implements MossStreamingDecodePipeline.Decoder {
        private final long delayMillis;
        private final int failAtBatch;
        private final List<Integer> batchSizes = new ArrayList<>();
        private final List<Boolean> finalBatches = new ArrayList<>();
        private final List<Integer> flattenedFrames = new ArrayList<>();

        private RecordingDecoder() {
            this(0L, -1);
        }

        private RecordingDecoder(long delayMillis, int failAtBatch) {
            this.delayMillis = delayMillis;
            this.failAtBatch = failAtBatch;
        }

        @Override
        public List<float[][]> decode(List<List<Integer>> frames, boolean finalBatch) throws Exception {
            if (delayMillis > 0L) {
                Thread.sleep(delayMillis);
            }
            int batchNumber = batchSizes.size() + 1;
            if (batchNumber == failAtBatch) {
                throw new IllegalStateException("codec failed");
            }
            batchSizes.add(frames.size());
            finalBatches.add(finalBatch);
            for (List<Integer> frame : frames) {
                flattenedFrames.add(frame.get(0));
            }
            return List.of();
        }

        @Override
        public void close() {
        }
    }

    private static final class ThreadedCodecExecution implements TtsCodecExecution, AutoCloseable {
        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        @Override
        public Task submit(Runnable work) {
            Future<?> future = executor.submit(work);
            return new Task() {
                @Override
                public boolean accepted() {
                    return true;
                }

                @Override
                public void cancel(String reason) {
                    future.cancel(true);
                }

                @Override
                public void await(BooleanSupplier cancellationRequested) throws Exception {
                    while (true) {
                        if (cancellationRequested != null && cancellationRequested.getAsBoolean()) {
                            future.cancel(true);
                            throw new CancellationException("cancelled");
                        }
                        try {
                            future.get(20L, TimeUnit.MILLISECONDS);
                            return;
                        } catch (TimeoutException ignored) {
                        } catch (ExecutionException failure) {
                            Throwable cause = failure.getCause();
                            if (cause instanceof Exception exception) {
                                throw exception;
                            }
                            if (cause instanceof Error error) {
                                throw error;
                            }
                            throw new IllegalStateException(cause);
                        }
                    }
                }
            };
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
