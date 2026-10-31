package com.rheinmetal.tianshu.function.echo;

/**
 * The protocol identity of this module.
 *
 * <p>It lives beside the module, not in the protocol adapter: runtime, playback, synthesis and the model
 * services all need the id for their own task specs, and none of them should have to import the top-layer
 * adapter just to name the module they belong to.</p>
 */
public final class TtsModuleId {

    public static final String VALUE = "module.tts";

    private TtsModuleId() {
    }
}
