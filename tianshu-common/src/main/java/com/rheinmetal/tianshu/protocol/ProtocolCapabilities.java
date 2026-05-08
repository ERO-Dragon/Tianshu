package com.rheinmetal.tianshu.protocol;

public final class ProtocolCapabilities {
    public static final String ASR_RECOGNIZE = "ASR_RECOGNIZE";
    public static final String IR_PARSE = "IR_PARSE";
    public static final String IR_EXECUTE = "IR_EXECUTE";
    public static final String LLM_CHAT = "LLM_CHAT";
    public static final String LLM_REPAIR = "LLM_REPAIR";
    public static final String LLM_FEEDBACK = "LLM_FEEDBACK";
    public static final String LLM_INTENT_CLASSIFY = "LLM_INTENT_CLASSIFY";
    public static final String LLM_COMMAND_REPAIR = "LLM_COMMAND_REPAIR";
    public static final String TTS_SPEAK = "TTS_SPEAK";
    public static final String TTS_ALERT = "TTS_ALERT";
    public static final String TTS_STOP = "TTS_STOP";
    public static final String UI_TOAST = "UI_TOAST";
    public static final String GEMINI_CARD_SHOW = "GEMINI_CARD.SHOW";
    public static final String SERVER_ACTION = "SERVER_ACTION";
    public static final String GRAPH_SHOW_RECIPE = "GRAPH.SHOW_RECIPE";
    public static final String GRAPH_CLOSE = "GRAPH.CLOSE";
    public static final String GRAPH_SUSPEND = "GRAPH.SUSPEND";
    public static final String GRAPH_RESUME = "GRAPH.RESUME";
    public static final String CHAT_ASSISTANT_SEND = "CHAT_ASSISTANT.SEND";
    public static final String CHAT_ASSISTANT_CLIENT_EVENT = "CHAT_ASSISTANT.CLIENT_EVENT";
    public static final String CHAT_ASSISTANT_INTERRUPT = "CHAT_ASSISTANT.INTERRUPT";
    public static final String CHAT_ASSISTANT_INCOMING_CHAT = "CHAT_ASSISTANT.INCOMING_CHAT";

    private ProtocolCapabilities() {
    }
}
