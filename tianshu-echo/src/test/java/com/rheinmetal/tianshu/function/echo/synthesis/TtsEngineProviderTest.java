package com.rheinmetal.tianshu.function.echo.synthesis;

import com.rheinmetal.tianshu.function.echo.runtime.TtsRequest;
import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The load/swap contract of {@link TtsEngineProvider}: a candidate that never becomes current is always
 * released, and the backend that is serving is only closed once its replacement has been adopted.
 */
class TtsEngineProviderTest {

    @Test
    void initializeErrorReleasesTheCandidateAndLeavesTheRunningBackendServing() {
        RecordingBackend running = new RecordingBackend(TtsBackendType.SHERPA, true);
        RecordingBackend candidate = new RecordingBackend(TtsBackendType.MOSS, true);
        OutOfMemoryError fatal = new OutOfMemoryError("synthetic initialize failure");
        candidate.failWith(fatal);
        TtsEngineProvider provider = providerUsing(running, candidate);

        assertSame(running, provider.acquire(model("model-a", TtsBackendType.SHERPA), false).backend());

        Error thrown = assertThrows(Error.class,
                () -> provider.acquire(model("model-b", TtsBackendType.MOSS), false));

        assertSame(fatal, thrown, "the fatal from initialize has to reach the caller unchanged");
        assertEquals(1, candidate.shutdowns, "the candidate that never became current must be released once");
        assertEquals(0, running.shutdowns, "a failed switch must not touch the backend that is serving");
        assertSame(running, provider.acquire(model("model-a", TtsBackendType.SHERPA), false).backend(),
                "the previous backend must still be the one in charge");
    }

    @Test
    void rejectedInitializeKeepsThePreviousBackendAndReleasesTheCandidate() {
        RecordingBackend running = new RecordingBackend(TtsBackendType.SHERPA, true);
        RecordingBackend candidate = new RecordingBackend(TtsBackendType.MOSS, false);
        TtsEngineProvider provider = providerUsing(running, candidate);

        provider.acquire(model("model-a", TtsBackendType.SHERPA), false);
        TtsEngineProvider.Load failed = provider.acquire(model("model-b", TtsBackendType.MOSS), false);

        assertEquals(TtsEngineProvider.Outcome.FAILED, failed.outcome());
        assertSame(running, failed.backend());
        assertEquals(1, candidate.shutdowns, "a candidate that reported failure must be released once");
        assertEquals(0, running.shutdowns);
    }

    @Test
    void successfulSwitchClosesThePreviousBackendExactlyOnce() {
        RecordingBackend first = new RecordingBackend(TtsBackendType.SHERPA, true);
        RecordingBackend second = new RecordingBackend(TtsBackendType.MOSS, true);
        TtsEngineProvider provider = providerUsing(first, second);

        assertEquals(TtsEngineProvider.Outcome.RELOADED,
                provider.acquire(model("model-a", TtsBackendType.SHERPA), false).outcome());
        assertEquals(TtsEngineProvider.Outcome.RELOADED,
                provider.acquire(model("model-b", TtsBackendType.MOSS), false).outcome());

        assertEquals(1, first.shutdowns, "the replaced backend must be closed");
        assertEquals(0, second.shutdowns, "the adopted backend must stay open");

        TtsEngineProvider.Load again = provider.acquire(model("model-b", TtsBackendType.MOSS), false);
        assertEquals(TtsEngineProvider.Outcome.UNCHANGED, again.outcome());
        assertSame(second, again.backend());
        assertEquals(1, first.shutdowns, "an unchanged acquire must not close anything");
        assertEquals(0, second.shutdowns);
    }

    /**
     * Reloading the model that is already running must not keep a second copy of it resident.
     *
     * <p>Its "fallback" would be another copy of the very files being reloaded, so overlapping the two buys
     * nothing but a doubled peak — which is what this check pins down by requiring the release to happen
     * before the replacement is even constructed.</p>
     */
    @Test
    void reloadingTheRunningModelReleasesItBeforeTheReplacementIsBuilt() {
        List<String> events = new java.util.ArrayList<>();
        RecordingBackend first = new RecordingBackend(TtsBackendType.SHERPA, true, events, "first");
        RecordingBackend second = new RecordingBackend(TtsBackendType.SHERPA, true, events, "second");
        TtsEngineProvider provider = providerUsing(first, second);
        TtsResolvedModel model = model("model-a", TtsBackendType.SHERPA);

        provider.acquire(model, false);
        assertEquals(List.of("init:first"), List.copyOf(events));

        assertEquals(TtsEngineProvider.Outcome.RELOADED, provider.acquire(model, true).outcome());

        assertEquals(List.of("init:first", "shutdown:first", "init:second"), List.copyOf(events),
                "the running copy has to be closed before the replacement starts loading");
        assertEquals(1, first.shutdowns);
        assertEquals(0, second.shutdowns);
    }

    private static TtsEngineProvider providerUsing(TtsBackend... backends) {
        Deque<TtsBackend> queue = new ArrayDeque<>(List.of(backends));
        return new TtsEngineProvider(new FakeGameEnvironment(), null, backendType -> queue.removeFirst());
    }

    private static TtsResolvedModel model(String directory, TtsBackendType backendType) {
        return new TtsResolvedModel(Path.of(directory), null, backendType);
    }

    private static final class RecordingBackend implements TtsBackend {
        private final TtsBackendType backendType;
        private final boolean initializes;
        private final List<String> events;
        private final String name;
        private boolean initialized;
        private Error initializationError;
        int shutdowns;

        private RecordingBackend(TtsBackendType backendType, boolean initializes) {
            this(backendType, initializes, new java.util.ArrayList<>(), backendType.name());
        }

        private RecordingBackend(TtsBackendType backendType, boolean initializes, List<String> events, String name) {
            this.backendType = backendType;
            this.initializes = initializes;
            this.events = events;
            this.name = name;
        }

        private void failWith(Error error) {
            this.initializationError = error;
        }

        @Override
        public boolean initialize(TtsResolvedModel model) {
            events.add("init:" + name);
            if (initializationError != null) {
                throw initializationError;
            }
            initialized = initializes;
            return initializes;
        }

        @Override
        public boolean isInitialized() {
            return initialized;
        }

        @Override
        public int sampleRate() {
            return 24_000;
        }

        @Override
        public void synthesize(TtsRequest request, TtsAudioSink sink) {
            throw new UnsupportedOperationException(backendType + " cannot synthesize in a unit test");
        }

        @Override
        public void interrupt() {
        }

        @Override
        public void shutdown() {
            events.add("shutdown:" + name);
            shutdowns++;
            initialized = false;
        }
    }
}
