package com.rheinmetal.tianshu.function.echo.synthesis;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.echo.synthesis.cloud.CloudTtsBackend;
import com.rheinmetal.tianshu.function.echo.synthesis.cloud.EdgeTtsCredentials;
import com.rheinmetal.tianshu.function.echo.synthesis.cloud.EdgeTtsTransport;
import com.rheinmetal.tianshu.function.echo.synthesis.cloud.JLayerMp3Decoder;
import com.rheinmetal.tianshu.function.echo.synthesis.cloud.JdkEdgeTtsConnectionFactory;
import com.rheinmetal.tianshu.function.echo.synthesis.cloud.TransportCloudTtsBackend;

import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Owns the one live TTS backend, and the selection that backend was built for.
 *
 * <p>A switch to a <em>different</em> selection builds the replacement before dropping what is running: the old
 * backend keeps serving until the new one is initialized, and a failed load therefore leaves the lane with
 * the selection it already had instead of silently taking voice away until the next world. The two backends
 * are resident together for the length of that load, which is the trade the ASR hotword reload makes — the
 * native-load gate serialises loads, and a swap has to overlap to avoid an empty window.</p>
 *
 * <p>A reload of the selection that is already running is not that case: its "fallback" would be another copy
 * of the same files, so it releases the running backend first and keeps the peak at one model.</p>
 *
 * <p>This is the only place a backend is constructed, and the candidate's initializer is created in the same
 * branch as the candidate itself, exactly once, so a backend is never paired with another kind's initializer.
 * The coordinator decides <em>when</em> a switch may run; this class only decides whether the switch
 * succeeded.</p>
 */
public final class TtsEngineProvider {

    /** What a load attempt left the lane with. */
    public enum Outcome {
        /** The requested selection was already running; nothing was loaded and nothing was closed. */
        UNCHANGED,
        /** A new backend is now in charge, and the previous one has been closed. */
        RELOADED,
        /** The candidate failed to initialize; nothing about the previous backend was changed. */
        FAILED
    }

    /** The backend in charge after an attempt, plus how the attempt ended. */
    public record Load(TtsBackend backend, Outcome outcome) {
    }

    /**
     * A constructed backend plus the one call that brings it up.
     *
     * <p>They travel together because they are built from the same selection: pairing them afterwards is where
     * a "wrong initializer for this backend" mistake would creep in.</p>
     */
    private record Candidate(TtsBackend backend, BooleanSupplier initialize) {
    }

    private final IGameEnvironment env;
    private final TtsCodecExecution codecExecution;
    private final Function<TtsBackendType, TtsBackend> backendFactory;
    private TtsBackend backend;
    private TtsBackendSelection loadedSelection;

    public TtsEngineProvider(IGameEnvironment env, TtsCodecExecution codecExecution) {
        this(env, codecExecution, null);
    }

    /**
     * Test seam: {@code backendFactory} replaces backend construction so a test can drive
     * {@code initialize} failure without loading native models. Production code passes {@code null}
     * and keeps the type switch below.
     */
    TtsEngineProvider(
            IGameEnvironment env,
            TtsCodecExecution codecExecution,
            Function<TtsBackendType, TtsBackend> backendFactory
    ) {
        this.env = env;
        this.codecExecution = codecExecution;
        this.backendFactory = backendFactory;
    }

    /**
     * Brings {@code selection} up.
     *
     * @param forceReload build a fresh backend even when the requested selection is the running one, which is
     *                    what "reload" means for a lane whose model file may have been replaced on disk
     */
    public synchronized Load acquire(TtsBackendSelection selection, boolean forceReload) {
        if (!forceReload && backend != null && backend.isInitialized() && sameSelection(selection)) {
            return new Load(backend, Outcome.UNCHANGED);
        }
        TtsBackend previous = backend;
        if (forceReload && previous != null && sameSelection(selection)) {
            // Reloading the selection that is already running. Keeping a second copy resident buys nothing here:
            // the fallback would be a copy of the very files that just failed to load, so the lane is no
            // better off — while two of the largest models resident at once is exactly the peak this module
            // tries to avoid. Release first, then load.
            releaseCurrent();
            previous = null;
        }
        Candidate candidate = candidate(selection);
        boolean adopted = false;
        Throwable pending = null;
        try {
            if (!candidate.initialize().getAsBoolean()) {
                // The candidate never becomes current: whatever was running keeps running, so a bad file or
                // a rejected switch cannot leave the lane silent.
                if (previous == null || !previous.isInitialized()) {
                    backend = null;
                    loadedSelection = null;
                    return new Load(null, Outcome.FAILED);
                }
                return new Load(previous, Outcome.FAILED);
            }
            backend = candidate.backend();
            loadedSelection = selection;
            // From here on the candidate is the lane's backend, so the release below must not close it.
            adopted = true;
            if (previous != null) {
                previous.shutdown();
            }
            return new Load(candidate.backend(), Outcome.RELOADED);
        } catch (Throwable failure) {
            pending = failure;
            throw failure;
        } finally {
            if (!adopted) {
                // A candidate that never became current is unreachable to every caller, and initialize can
                // throw an Error (which no catch clause further up is obliged to clean up), so this frame is
                // the only owner that can release what it already allocated.
                releaseCandidate(candidate.backend(), pending);
            }
        }
    }

    public synchronized void shutdown() {
        releaseCurrent();
    }

    private void releaseCurrent() {
        if (backend != null) {
            backend.shutdown();
            backend = null;
        }
        loadedSelection = null;
    }

    private void releaseCandidate(TtsBackend candidate, Throwable pending) {
        try {
            candidate.shutdown();
        } catch (RuntimeException | Error releaseFailure) {
            if (pending != null) {
                // The load failure is what the caller has to see, so a release failure is attached to it
                // instead of replacing it; a caller that reaches this without a failure still gets it.
                pending.addSuppressed(releaseFailure);
                return;
            }
            throw releaseFailure;
        }
    }

    /**
     * Builds the backend for a selection together with the initializer that belongs to it.
     *
     * <p>The switch is exhaustive over the backend type, which the Java 17 toolchain these modules compile
     * with accepts: pattern switches need 21, while an enum switch still fails to compile when a new backend
     * type is added without a branch.</p>
     */
    private Candidate candidate(TtsBackendSelection selection) {
        return switch (selection.backendType()) {
            case CLOUD -> {
                CloudTtsBackend created = (CloudTtsBackend) createBackend(TtsBackendType.CLOUD);
                CloudTtsBackendSelection cloud = (CloudTtsBackendSelection) selection;
                yield new Candidate(created, () -> created.initialize(cloud.profile()));
            }
            case SHERPA, MOSS, POCKET -> {
                LocalTtsBackendSelection local = (LocalTtsBackendSelection) selection;
                TtsBackend created = createBackend(local.backendType());
                yield new Candidate(created, () -> ((LocalTtsBackend) created).initialize(local.model()));
            }
        };
    }

    private TtsBackend createBackend(TtsBackendType backendType) {
        if (backendFactory != null) {
            return backendFactory.apply(backendType);
        }
        return switch (backendType) {
            case MOSS -> new MossTtsBackend(env, codecExecution);
            case POCKET -> new com.rheinmetal.tianshu.function.echo.synthesis.pocket.PocketTtsBackend(env, codecExecution);
            case SHERPA -> new SherpaOnnxTtsBackend(env);
            case CLOUD -> new TransportCloudTtsBackend(
                    new EdgeTtsTransport(EdgeTtsCredentials::current, new JdkEdgeTtsConnectionFactory()),
                    new JLayerMp3Decoder());
        };
    }

    private boolean sameSelection(TtsBackendSelection selection) {
        return loadedSelection != null && loadedSelection.equals(selection);
    }
}