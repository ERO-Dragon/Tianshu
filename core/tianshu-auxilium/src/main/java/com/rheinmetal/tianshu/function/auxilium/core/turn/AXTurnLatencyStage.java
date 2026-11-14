package com.rheinmetal.tianshu.function.auxilium.core.turn;

/** Stable debug milestones for one AX chat turn. */
public enum AXTurnLatencyStage {
    IA_DELIVERY,
    PROMPT_READY,
    LLM_SUBMITTED,
    LLM_FIRST_TOKEN,
    AX_FIRST_SENTENCE,
    TTS_SUBMITTED
}
