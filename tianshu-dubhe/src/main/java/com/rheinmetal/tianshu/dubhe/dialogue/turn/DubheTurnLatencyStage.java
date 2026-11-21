package com.rheinmetal.tianshu.dubhe.dialogue.turn;

/** Stable debug milestones for one AX chat turn. */
public enum DubheTurnLatencyStage {
    IA_DELIVERY,
    PROMPT_READY,
    LLM_SUBMITTED,
    LLM_FIRST_TOKEN,
    DUBHE_FIRST_SENTENCE,
    TTS_SUBMITTED
}
