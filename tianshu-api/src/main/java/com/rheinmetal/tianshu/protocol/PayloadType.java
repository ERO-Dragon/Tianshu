package com.rheinmetal.tianshu.protocol;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wire vocabulary of payload kinds.
 *
 * <p>The kernel registers the kinds it ships with as constants. A module that introduces a kind of its
 * own calls {@link #of(String)} (or {@link #of(String, Class)}) and does <em>not</em> have to edit this
 * class — that is what keeps the kernel from shipping a release for every module.</p>
 *
 * <p>Identity follows the name: {@link #of(String)} always returns the canonical instance, and two
 * instances with the same name are equal. A name may be bound to exactly one payload class; binding it to
 * a second class is rejected instead of failing later at message validation time.</p>
 */
public final class PayloadType {

    private static final Map<String, PayloadType> BY_NAME = new ConcurrentHashMap<>();
    private static final Map<String, Class<? extends ITianshuPayload>> BY_CLASS_OF_NAME =
            new ConcurrentHashMap<>();

    public static final PayloadType NONE = register("NONE");
    /** Backed by {@code TextPayload}; exercised by the dialogue contract validator. */
    public static final PayloadType TEXT = register("TEXT");
    public static final PayloadType ASR_SPEECH_ACTIVITY = register("ASR_SPEECH_ACTIVITY");
    public static final PayloadType ASR_TEXT = register("ASR_TEXT");
    public static final PayloadType IR_RESULT = register("IR_RESULT");
    public static final PayloadType LLM_PROMPT_REQUEST = register("LLM_PROMPT_REQUEST");
    public static final PayloadType LLM_PROMPT_RESULT = register("LLM_PROMPT_RESULT");
    public static final PayloadType LLM_PROMPT_STREAM_CHUNK = register("LLM_PROMPT_STREAM_CHUNK");
    public static final PayloadType LLM_PRIMITIVE_QUERY = register("LLM_PRIMITIVE_QUERY");
    public static final PayloadType LLM_PRIMITIVE_RESULT = register("LLM_PRIMITIVE_RESULT");
    public static final PayloadType LLM_STATUS = register("LLM_STATUS");
    public static final PayloadType LLM_CACHE_MANAGE = register("LLM_CACHE_MANAGE");
    public static final PayloadType LLM_CACHE_MANAGE_RESULT = register("LLM_CACHE_MANAGE_RESULT");
    public static final PayloadType PRESENCE_CONTEXT_QUERY = register("PRESENCE_CONTEXT_QUERY");
    public static final PayloadType PRESENCE_CONTEXT_SNAPSHOT = register("PRESENCE_CONTEXT_SNAPSHOT");
    public static final PayloadType PRESENCE_ACTIVITY = register("PRESENCE_ACTIVITY");
    public static final PayloadType TTS_TEXT = register("TTS_TEXT");
    public static final PayloadType TTS_PLAYBACK_STATUS = register("TTS_PLAYBACK_STATUS");
    public static final PayloadType TTS_REQUEST_STATUS = register("TTS_REQUEST_STATUS");
    public static final PayloadType TTS_AUDIO = register("TTS_AUDIO");
    public static final PayloadType TTS_AUDIO_ACK = register("TTS_AUDIO_ACK");
    public static final PayloadType CANCEL = register("CANCEL");
    public static final PayloadType MODULE_STATUS = register("MODULE_STATUS");
    public static final PayloadType DIALOGUE_ARBITRATION_REQUEST = register("DIALOGUE_ARBITRATION_REQUEST");
    public static final PayloadType DIALOGUE_ARBITRATION_RESULT = register("DIALOGUE_ARBITRATION_RESULT");
    public static final PayloadType DIALOGUE_PARTICIPANT_REGISTER = register("DIALOGUE_PARTICIPANT_REGISTER");
    public static final PayloadType DIALOGUE_PARTICIPANT_UNREGISTER = register("DIALOGUE_PARTICIPANT_UNREGISTER");
    public static final PayloadType DIALOGUE_SESSION_CONTROL = register("DIALOGUE_SESSION_CONTROL");
    public static final PayloadType DIALOGUE_SESSION_EVENT = register("DIALOGUE_SESSION_EVENT");
    public static final PayloadType DIALOGUE_OWNER_PREVIEW = register("DIALOGUE_OWNER_PREVIEW");
    public static final PayloadType DIALOGUE_DELIVERY = register("DIALOGUE_DELIVERY");
    public static final PayloadType DIALOGUE_CONTEXT_INPUT = register("DIALOGUE_CONTEXT_INPUT");
    public static final PayloadType DIALOGUE_SCORE_REQUEST = register("DIALOGUE_SCORE_REQUEST");
    public static final PayloadType DIALOGUE_SCORE_RESPONSE = register("DIALOGUE_SCORE_RESPONSE");
    /** One world event a producer already worded, handed to AX together with its tier. */
    public static final PayloadType AX_WORLD_EVENT = register("AX_WORLD_EVENT");
    /** Escape hatch for module payloads that live outside the kernel's vocabulary. */
    public static final PayloadType CUSTOM = register("CUSTOM");

    private final String name;

    private PayloadType(String name) {
        this.name = name;
    }

    /** The canonical instance for {@code name}; an unknown name is registered on first use. */
    public static PayloadType of(String name) {
        return register(requireName(name));
    }

    /**
     * The canonical instance for {@code name}, bound to {@code payloadClass}.
     *
     * @throws IllegalStateException when the name is already bound to a different payload class
     */
    public static PayloadType of(String name, Class<? extends ITianshuPayload> payloadClass) {
        String effectiveName = requireName(name);
        if (payloadClass == null) {
            throw new IllegalArgumentException("payload class must not be null for " + effectiveName);
        }
        Class<? extends ITianshuPayload> already = BY_CLASS_OF_NAME.putIfAbsent(effectiveName, payloadClass);
        if (already != null && already != payloadClass) {
            throw new IllegalStateException("payload type " + effectiveName + " is already bound to "
                    + already.getName() + " and cannot also be " + payloadClass.getName());
        }
        return register(effectiveName);
    }

    /** The payload class bound to {@code name}, or {@code null} when the name carries no binding. */
    public static Class<? extends ITianshuPayload> payloadClassOf(String name) {
        return name == null ? null : BY_CLASS_OF_NAME.get(name.trim());
    }

    public static boolean isRegistered(String name) {
        return name != null && BY_NAME.containsKey(name.trim());
    }

    /** Every registered kind, ordered by name; used by diagnostics and tests. */
    public static Collection<PayloadType> values() {
        return BY_NAME.values().stream()
                .sorted((left, right) -> left.name.compareTo(right.name))
                .toList();
    }

    public String name() {
        return name;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PayloadType that && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("payload type name must not be blank");
        }
        return name.trim();
    }

    private static PayloadType register(String name) {
        return BY_NAME.computeIfAbsent(name, PayloadType::new);
    }
}
