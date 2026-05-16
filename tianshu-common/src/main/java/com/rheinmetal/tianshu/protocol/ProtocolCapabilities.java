package com.rheinmetal.tianshu.protocol;

public final class ProtocolCapabilities {
    public static final String ASR_RECOGNIZE = "ASR_RECOGNIZE";
    public static final String IR_PARSE = "IR_PARSE";
    public static final String IR_EXECUTE = "IR_EXECUTE";
    public static final String LLM_CHAT = "LLM_CHAT";
    public static final String LLM_FEEDBACK = "LLM_FEEDBACK";
    public static final String LLM_TASK_REQUEST = "LLM_TASK_REQUEST";
    public static final String LLM_RAG_PATH_RESOLVE = "LLM.RAG_PATH_RESOLVE";
    public static final String TTS_SPEAK = "TTS_SPEAK";
    public static final String TTS_ALERT = "TTS_ALERT";
    public static final String TTS_STOP = "TTS_STOP";
    public static final String TTS_CONTROL = "TTS_CONTROL";
    public static final String UI_TOAST = "UI_TOAST";
    public static final String CORE_CAPABILITY_PROBE = "CORE.CAPABILITY_PROBE";
    public static final String INTEGRATION_MODULE_REGISTER = "INTEGRATION.MODULE_REGISTER";
    public static final String INTEGRATION_MODULE_UNREGISTER = "INTEGRATION.MODULE_UNREGISTER";
    public static final String DIALOGUE_ARBITRATE = "DIALOGUE.ARBITRATE";
    public static final String DIALOGUE_PARTICIPANT_REGISTER = "DIALOGUE.PARTICIPANT_REGISTER";
    public static final String DIALOGUE_PARTICIPANT_UNREGISTER = "DIALOGUE.PARTICIPANT_UNREGISTER";
    public static final String DIALOGUE_SESSION_CONTROL = "DIALOGUE.SESSION_CONTROL";
    public static final String DIALOGUE_LLM_USAGE_AUTHORIZE = "DIALOGUE.LLM_USAGE_AUTHORIZE";

    private ProtocolCapabilities() {
    }
}
