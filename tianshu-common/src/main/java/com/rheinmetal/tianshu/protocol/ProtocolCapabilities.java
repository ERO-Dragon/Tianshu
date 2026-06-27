package com.rheinmetal.tianshu.protocol;

public final class ProtocolCapabilities {
    public static final String IR_PARSE = "IR_PARSE";
    public static final String LLM_REQUEST = "LLM.REQUEST";
    public static final String LLM_CACHE_MANAGE = "LLM.CACHE_MANAGE";
    public static final String LLM_PRIMITIVE_QUERY = "LLM.PRIMITIVE_QUERY";
    public static final String PRESENCE_QUERY_CONTEXT = "PRESENCE.QUERY_CONTEXT";
    public static final String TTS_SPEAK = "TTS_SPEAK";
    public static final String TTS_SYNTHESIZE = "TTS_SYNTHESIZE";
    public static final String TTS_CONTROL = "TTS_CONTROL";
    public static final String DIALOGUE_ARBITRATE = "DIALOGUE.ARBITRATE";
    public static final String DIALOGUE_PARTICIPANT_REGISTER = "DIALOGUE.PARTICIPANT_REGISTER";
    public static final String DIALOGUE_PARTICIPANT_UNREGISTER = "DIALOGUE.PARTICIPANT_UNREGISTER";
    public static final String DIALOGUE_SESSION_CONTROL = "DIALOGUE.SESSION_CONTROL";
    public static final String DIALOGUE_LLM_USAGE_AUTHORIZE = "DIALOGUE.LLM_USAGE_AUTHORIZE";

    private ProtocolCapabilities() {
    }
}
