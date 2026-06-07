package com.rheinmetal.tianshu.protocol;

public final class ProtocolTopics {
    public static final String UI_STATUS = "UI_STATUS";
    public static final String DEBUG_TRACE = "DEBUG_TRACE";
    public static final String INPUT_ASR_FINAL_TEXT = "INPUT.ASR_FINAL_TEXT";
    public static final String INPUT_KEY_ACTION = "INPUT.KEY_ACTION";
    public static final String INPUT_CHAT_TEXT = "INPUT.CHAT_TEXT";
    public static final String IR_RESULT = "IR.RESULT";
    public static final String IR_COMMAND_EXECUTED = "IR.COMMAND_EXECUTED";
    public static final String SYSTEM_RUNTIME_INTERRUPT = "SYSTEM.RUNTIME_INTERRUPT";
    public static final String CORE_READY = "CORE.READY";
    public static final String CORE_SHUTDOWN = "CORE.SHUTDOWN";
    public static final String CORE_CAPABILITY_CHANGED = "CORE.CAPABILITY_CHANGED";
    public static final String CORE_LIFECYCLE = "CORE.LIFECYCLE";
    public static final String RESOURCE_RELOADED = "RESOURCE.RELOADED";
    public static final String LANGUAGE_RELOADED = "LANGUAGE.RELOADED";
    public static final String RESOURCE_EVENTS = "RESOURCE.EVENTS";
    public static final String STATE_SUMMARY_CHANGED = "STATE_SUMMARY.CHANGED";
    public static final String GUI_CONTRIBUTION_CHANGED = "GUI.CONTRIBUTION_CHANGED";
    public static final String TTS_PLAYBACK = "TTS.PLAYBACK";
    public static final String FEEDBACK_EMIT = "FEEDBACK.EMIT";
    public static final String SETTINGS_EVENT = "SETTINGS.EVENT";
    public static final String DIALOGUE_SESSION_EVENTS = "DIALOGUE.SESSION_EVENTS";

    private ProtocolTopics() {
    }
}
