package com.rheinmetal.tianshu.function.lab.acceptance;

import com.rheinmetal.tianshu.client.host.game.ClientGameContextPort;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

/**
 * The live handles a case needs but that only the module runtime can supply.
 *
 * <p>Cases are static utilities over the platform's registries; most need nothing else. The ones that exercise module
 * execution or read the game do — and reaching them by scanning internals would be a second, invented path beside the
 * real one. The bench therefore receives the same {@link ModuleRuntimeAccess} and {@link ClientGameContextPort} the
 * module itself was assembled with, and skips those cases when they are absent (a bare JVM has neither).</p>
 */
public final class AcceptanceEnvironment {

    private static volatile ModuleRuntimeAccess runtime;
    private static volatile ClientGameContextPort gameContext;

    private AcceptanceEnvironment() {
    }

    public static void install(ModuleRuntimeAccess access, ClientGameContextPort context) {
        runtime = access;
        gameContext = context;
    }

    public static void clear() {
        runtime = null;
        gameContext = null;
    }

    /** @return the module runtime, or {@code null} when this bench runs without one */
    public static ModuleRuntimeAccess runtime() {
        return runtime;
    }

    /** @return the game's read-only context, or {@code null} when this bench runs without a client */
    public static ClientGameContextPort gameContext() {
        return gameContext;
    }
}