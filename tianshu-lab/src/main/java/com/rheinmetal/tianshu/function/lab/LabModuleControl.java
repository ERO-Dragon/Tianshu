package com.rheinmetal.tianshu.function.lab;

/**
 * What the bench settings page can see and do.
 *
 * <p>Small on purpose: the page reports whether the fake participants are registered, what the last
 * delivery looked like and whether a session is still held, and it can release or extend that session.
 * Everything else is observed in chat by the debug echo.</p>
 */
public interface LabModuleControl {
    boolean debugEnabled();

    boolean participantsRegistered();

    boolean sessionHeld();

    boolean autoRelease();

    void setAutoRelease(boolean autoRelease);

    void registerParticipants();

    void unregisterParticipants();

    void releaseCurrentSession();

    void extendCurrentSession();

    LabStatus status();

    /** Immutable snapshot for the page; {@code lastText} is the repaired text of the last delivery. */
    record LabStatus(
            boolean debugEnabled,
            boolean participantsRegistered,
            boolean sessionHeld,
            String sessionId,
            String ownerParticipantId,
            String lastDeliveryText,
            String lastError
    ) {
    }
}