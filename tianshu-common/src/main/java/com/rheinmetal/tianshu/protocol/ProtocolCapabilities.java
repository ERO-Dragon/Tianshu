package com.rheinmetal.tianshu.protocol;

public final class ProtocolCapabilities {
    public static final String ASR_RECOGNIZE = "ASR_RECOGNIZE";
    public static final String IR_PARSE = "IR_PARSE";
    public static final String LLM_CHAT = "LLM_CHAT";
    public static final String LLM_REPAIR = "LLM_REPAIR";
    public static final String TTS_SPEAK = "TTS_SPEAK";
    public static final String TTS_ALERT = "TTS_ALERT";
    public static final String UI_TOAST = "UI_TOAST";
    public static final String GEMINI_CARD_SHOW = "GEMINI_CARD.SHOW";
    public static final String SERVER_ACTION = "SERVER_ACTION";
    public static final String GRAPH_SHOW_RECIPE = "GRAPH.SHOW_RECIPE";
    public static final String GRAPH_CLOSE = "GRAPH.CLOSE";
    public static final String GRAPH_SUSPEND = "GRAPH.SUSPEND";
    public static final String GRAPH_RESUME = "GRAPH.RESUME";

    private ProtocolCapabilities() {
    }
}
