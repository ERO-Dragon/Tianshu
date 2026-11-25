package com.rheinmetal.tianshu.lab.acceptance;

import com.rheinmetal.tianshu.client.host.ClientScheduler;
import com.rheinmetal.tianshu.client.host.game.ClientGameContextPort;
import com.rheinmetal.tianshu.lab.LabHostTrace;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

import java.util.function.Function;

/**
 * The live handles a case needs but that only the module runtime can supply.
 *
 * <p>Cases are static utilities over the platform's registries; most need nothing else. The ones that exercise module
 * execution or read the game do — and reaching them by scanning internals would be a second, invented path beside the
 * real one. The bench therefore receives the same {@link ModuleRuntimeAccess} and {@link ClientGameContextPort} the
 * module itself was assembled with, and skips those cases when they are absent (a bare JVM has neither).</p>
 *
 * <p>{@link LabHostTrace} is the same rule for the host: what happened to this module is not in any registry the bench
 * may read, so the module hands its own record over.</p>
 */
public final class AcceptanceEnvironment {

    private static volatile Binding binding = new Binding(null, null, null, null);

    private AcceptanceEnvironment() {
    }

    public static void install(ModuleRuntimeAccess access, ClientGameContextPort context,
                               ClientScheduler clientScheduler, LabHostTrace host) {
        binding = new Binding(access, context, clientScheduler, host);
    }

    public static void clear() {
        binding = new Binding(null, null, null, null);
    }

    /** @return the module runtime, or {@code null} when this bench runs without one */
    public static ModuleRuntimeAccess runtime() {
        return binding.runtime();
    }

    /** @return the game's read-only context, or {@code null} when this bench runs without a client */
    public static ClientGameContextPort gameContext() {
        return binding.gameContext();
    }

    /** @return this module's own record of its life in the host, or {@code null} when the bench runs standalone */
    public static LabHostTrace host() {
        return binding.host();
    }

    /** Runs a live game read on the client thread while keeping the acceptance suite on its worker. */
    public static <T> T readGame(Function<ClientGameContextPort, T> read) {
        Binding current = binding;
        ClientGameContextPort context = current.gameContext();
        if (context == null) {
            return null;
        }
        ClientScheduler clientScheduler = current.scheduler();
        if (clientScheduler == null) {
            throw new IllegalStateException("client scheduler is unavailable");
        }
        return clientScheduler.supply(() -> read.apply(context)).join();
    }

    private record Binding(ModuleRuntimeAccess runtime, ClientGameContextPort gameContext,
                           ClientScheduler scheduler, LabHostTrace host) {
    }
}