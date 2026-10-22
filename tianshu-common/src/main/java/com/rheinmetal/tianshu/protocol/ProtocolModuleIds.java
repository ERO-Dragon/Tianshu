package com.rheinmetal.tianshu.protocol;

/**
 * The protocol identity of every module that ships with Tianshu.
 *
 * <p>These strings appear in envelope {@code sourceId}s, task specs, module status, diagnostics and
 * capability ownership. They are collected here so a module id exists exactly once: a front that
 * spells it out locally drifts the moment the id changes, and the drift is invisible until a status
 * row silently disappears.</p>
 *
 * <p>External modules are not listed here — their ids are their own business, and the protocol only
 * requires them to be non-blank.</p>
 */
public final class ProtocolModuleIds {

    public static final String ASR = "module.asr";
    public static final String TTS = "module.tts";
    public static final String LLM = "module.llm";
    public static final String AX = "module.ax";
    public static final String IA = "module.ia";
    public static final String IR = "module.ir";
    public static final String PRESENCE = "module.presence";

    /** Optional platform module that loads the ONNX native runtime before any speech front uses it. */
    public static final String PLATFORM_ONNX_RUNTIME = "module.platform.onnx_runtime";

    private ProtocolModuleIds() {
    }
}
