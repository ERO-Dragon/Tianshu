package com.rheinmetal.tianshu.protocol;

/**
 * Wire vocabulary of payload kinds.
 *
 * <p>Every constant here must have a payload that is actually constructed somewhere. Constants whose
 * payload was never built were removed together with those payloads — an enum value nobody sends is
 * just a misleading promise about what the protocol can carry.
 */
public enum PayloadType {
    NONE,
    /** Backed by {@code TextPayload}; exercised by the dialogue contract validator. */
    TEXT,
    ASR_SPEECH_ACTIVITY,
    ASR_TEXT,
    IR_RESULT,
    LLM_PROMPT_REQUEST,
    LLM_PROMPT_RESULT,
    LLM_PROMPT_STREAM_CHUNK,
    LLM_PRIMITIVE_QUERY,
    LLM_PRIMITIVE_RESULT,
    LLM_STATUS,
    LLM_CACHE_MANAGE,
    LLM_CACHE_MANAGE_RESULT,
    PRESENCE_CONTEXT_QUERY,
    PRESENCE_CONTEXT_SNAPSHOT,
    PRESENCE_ACTIVITY,
    TTS_TEXT,
    TTS_PLAYBACK_STATUS,
    TTS_REQUEST_STATUS,
    TTS_AUDIO,
    TTS_AUDIO_ACK,
    CANCEL,
    MODULE_STATUS,
    DIALOGUE_ARBITRATION_REQUEST,
    DIALOGUE_ARBITRATION_RESULT,
    DIALOGUE_PARTICIPANT_REGISTER,
    DIALOGUE_PARTICIPANT_UNREGISTER,
    DIALOGUE_SESSION_CONTROL,
    DIALOGUE_SESSION_EVENT,
    DIALOGUE_OWNER_PREVIEW,
    DIALOGUE_DELIVERY,
    DIALOGUE_CONTEXT_INPUT,
    DIALOGUE_LLM_USAGE_AUTHORIZATION_REQUEST,
    DIALOGUE_LLM_USAGE_AUTHORIZATION_RESULT,
    DIALOGUE_SCORE_REQUEST,
    DIALOGUE_SCORE_RESPONSE,
    CUSTOM
}
