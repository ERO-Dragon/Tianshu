package com.rheinmetal.tianshu.protocol;

/**
 * The protocol identities the kernel and the modules it composes by default share.
 *
 * <p>These strings appear in envelope {@code sourceId}s, task specs, module status, diagnostics and
 * capability ownership. They are collected here so an id exists exactly once: a front that
 * spells it out locally drifts the moment the id changes, and the drift is invisible until a status
 * row silently disappears.</p>
 *
 * <p>A module extracted into its own optional artifact owns its id itself and is <em>not</em> listed here:
 * presence is the first such module ({@code PresenceProtocolAdapter.MODULE_ID}). External modules were never
 * listed — their ids are their own business, and the protocol only requires them to be non-blank.</p>
 */
public final class ProtocolModuleIds {

    public static final String ASR = "module.asr";
    public static final String TTS = "module.tts";
    public static final String LLM = "module.llm";
    public static final String AX = "module.ax";
    public static final String IA = "module.ia";
    public static final String IR = "module.ir";

    /** Optional platform module that loads the ONNX native runtime before any speech front uses it. */
    public static final String PLATFORM_ONNX_RUNTIME = "module.platform.onnx_runtime";

    private ProtocolModuleIds() {
    }
}
