package com.rheinmetal.tianshu.protocol;

public final class ProtocolTopics {
    public static final String INPUT_ASR_SPEECH_ACTIVITY = "INPUT.ASR_SPEECH_ACTIVITY";
    public static final String INPUT_ASR_FINAL_TEXT = "INPUT.ASR_FINAL_TEXT";
    public static final String IR_RESULT = "IR.RESULT";
    public static final String SYSTEM_RUNTIME_INTERRUPT = "SYSTEM.RUNTIME_INTERRUPT";
    public static final String LLM_STATUS = "LLM.STATUS";
    public static final String TTS_PLAYBACK = "TTS.PLAYBACK";
    public static final String TTS_REQUEST_STATUS = "TTS.REQUEST_STATUS";
    public static final String MODULE_STATUS = "MODULE.STATUS";
    public static final String DIALOGUE_SESSION_EVENTS = "DIALOGUE.SESSION_EVENTS";
    /** Raw world events as the platform observed them; the aggregation into windows belongs to the consumer. */
    public static final String WORLD_EVENT = "GAME.WORLD_EVENT";

    private ProtocolTopics() {
    }
}

