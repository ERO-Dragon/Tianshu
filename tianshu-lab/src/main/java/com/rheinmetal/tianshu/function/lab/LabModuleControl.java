package com.rheinmetal.tianshu.function.lab;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueHoldTier;

/**
 * What the bench settings page can see and do.
 *
 * <p>Small on purpose: the page reports which participants are registered, what the last delivery looked
 * like and whether a session is still held, and it can release or extend that session. Everything else is
 * observed in chat by the debug echo.</p>
 */
public interface LabModuleControl {
    boolean debugEnabled();

    boolean participantsRegistered();

    /** Registered participant ids in registration order, e.g. {@code "alpha,beta,gamma"}. */
    String registeredParticipants();

    boolean sessionHeld();

    boolean autoRelease();

    void setAutoRelease(boolean autoRelease);

    /** Crosshair entity filter; blank means "any entity the crosshair is on". */
    String crosshairFilter();

    void setCrosshairFilter(String value);

    /** Held item id that makes beta claim; blank leaves beta unregistered. */
    String heldItemId();

    void setHeldItemId(String value);

    /** Comma-separated wake words for gamma; blank leaves gamma unregistered. */
    String wakeWords();

    void setWakeWords(String value);

    /** How long the winner keeps the turn; all three participants use the same tier. */
    DialogueHoldTier holdTier();

    void setHoldTier(DialogueHoldTier holdTier);

    void registerParticipants();

    void unregisterParticipants();

    void releaseCurrentSession();

    void extendCurrentSession();

    LabStatus status();

    /** Immutable snapshot for the page; {@code lastText} is the repaired text of the last delivery. */
    record LabStatus(
            boolean debugEnabled,
            boolean participantsRegistered,
            String registeredParticipants,
            boolean sessionHeld,
            String sessionId,
            String ownerParticipantId,
            String lastDeliveryText,
            String lastError
    ) {
    }
}