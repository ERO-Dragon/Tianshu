package com.rheinmetal.tianshu.protocol;

public enum PayloadType {
    NONE,
    TEXT,
    ASR_TEXT,
    IR_COMMAND,
    IR_PARSE,
    IR_RESULT,
    LLM_PROMPT,
    LLM_INTENT_CLASSIFY,
    LLM_COMMAND_REPAIR,
    LLM_TEXT_CHUNK,
    TTS_TEXT,
    TTS_AUDIO,
    UI_TOAST,
    SERVER_ACTION,
    ALERT,
    FEEDBACK,
    SYSTEM_STATE,
    GRAPH_REQUEST,
    INPUT_ACTION,
    STATUS,
    ERROR,
    HEARTBEAT,
    PROGRESS,
    CANCEL,
    SNAPSHOT,
    CUSTOM
}

