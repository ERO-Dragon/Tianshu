package com.rheinmetal.tianshu.function.auxilium.bridge;

import com.rheinmetal.tianshu.function.auxilium.input.AXDialogueInput;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueReleaseReason;

/**
 * What this module needs from the dialogue domain, said without that domain's vocabulary.
 *
 * <p>Everything else in the module depends on this interface, so the intake module stops being a compile
 * dependency of the gateway, the turn pipeline and the orchestrator. When the dialogue domain is not
 * installed, {@link #NONE} answers: the inbound capability is never opened (nothing can deliver a spoken
 * turn) and the outbound calls do nothing (there is nobody to register with).</p>
 */
public interface AXDialogueChannel {

    /** Opens the inbound dialogue capability; the handler receives this module's own input type. */
    void openInbound(AXDialogueInputHandler handler);

    /** Whether a participant registry exists to register with. */
    boolean participantRegistrationAvailable();

    void registerParticipant(DialogueParticipantDescriptor descriptor);

    void unregisterParticipant();

    void releaseSession(TianshuEnvelope parent, AXDialogueInput input, DialogueReleaseReason reason);

    void extendSession(TianshuEnvelope parent, AXDialogueInput input, long requestedProcessingMillis);

    /** The stand-in used when the dialogue domain is absent: every call is a no-op and nothing can arrive. */
    AXDialogueChannel NONE = new AXDialogueChannel() {
        @Override
        public void openInbound(AXDialogueInputHandler handler) {
        }

        @Override
        public boolean participantRegistrationAvailable() {
            return false;
        }

        @Override
        public void registerParticipant(DialogueParticipantDescriptor descriptor) {
        }

        @Override
        public void unregisterParticipant() {
        }

        @Override
        public void releaseSession(TianshuEnvelope parent, AXDialogueInput input, DialogueReleaseReason reason) {
        }

        @Override
        public void extendSession(TianshuEnvelope parent, AXDialogueInput input, long requestedProcessingMillis) {
        }
    };
}
