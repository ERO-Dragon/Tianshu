package com.rheinmetal.tianshu.function.weaving.settings;

/**
 * How a provider expresses reasoning depth on the wire.
 *
 * <p>The request always carries a provider-neutral effort; this enum only says which field the
 * endpoint understands. It is a property of the provider profile, not of a single model: the same
 * endpoint uses the same wire shape for every model it serves.</p>
 */
public enum LlmReasoningWire {
    /** No reasoning control: the field is omitted and the endpoint's default applies. */
    NONE,
    /** OpenAI-style top-level {@code reasoning_effort}; cannot disable reasoning, so off maps to low. */
    OPENAI_EFFORT,
    /** DeepSeek thinking mode: {@code thinking.type} plus {@code reasoning_effort}. */
    DEEPSEEK_THINKING
}
