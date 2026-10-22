package com.rheinmetal.tianshu.function.asr;

import com.rheinmetal.tianshu.core.runtime.RuntimeCapability;

/**
 * The two dimensions of speech input readiness, deliberately kept apart.
 *
 * <p>{@link #INPUT} answers "is the module online" — its controller, capture service and input gateway
 * are bound, so a voice key press reaches the module and can be answered. {@link #MODEL} answers "is
 * the speech model loaded" — the answer the recognition lane actually needs, and the one that stays
 * false until a catalog model is both selected and present on disk.
 *
 * <p>They used to be one capability, and that made a missing model look like a dead module: the engine
 * marked the single capability failed, so nothing could be accepted and the player got a generic
 * "waking up" message for a state that would never change on its own.
 */
public final class AsrRuntimeCapabilities {
    /** Module online: the input channel exists and can answer a voice key press. */
    public static final RuntimeCapability INPUT = RuntimeCapability.of("capability.asr.input");
    /** Speech model available: recognition can actually run right now. */
    public static final RuntimeCapability MODEL = RuntimeCapability.of("capability.asr.model");

    private AsrRuntimeCapabilities() {
    }
}
