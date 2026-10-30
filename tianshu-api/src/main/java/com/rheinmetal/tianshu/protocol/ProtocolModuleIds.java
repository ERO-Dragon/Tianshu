package com.rheinmetal.tianshu.protocol;

/**
 * The ids of the platform's own modules.
 *
 * <p>Feature modules own their ids themselves ({@code AsrProtocolAdapter.MODULE_ID}, {@code AXModule.MODULE_ID},
 * …): the kernel derives whatever it needs from an id it is handed, so it never has to enumerate them. What is
 * left here are the parts the host itself ships — the native runtime loader and the audio bridge — which are
 * not features and have no module jar of their own.</p>
 */
public final class ProtocolModuleIds {

    /** Optional platform module that loads the ONNX native runtime before any speech front uses it. */
    public static final String PLATFORM_ONNX_RUNTIME = "module.platform.onnx_runtime";

    /** The host's audio bridge: it reports playback metrics as a platform component, not as a feature. */
    public static final String PLATFORM_AUDIO = "module.platform.audio";

    private ProtocolModuleIds() {
    }
}
