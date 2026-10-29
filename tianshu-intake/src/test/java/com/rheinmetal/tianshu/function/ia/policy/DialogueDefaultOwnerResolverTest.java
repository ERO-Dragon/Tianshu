package com.rheinmetal.tianshu.function.ia.policy;

import com.rheinmetal.tianshu.function.ia.settings.IaDefaultOwnerSettings;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueHoldTier;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTriggerGroup;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueDefaultOwnerResolverTest {
    private final DialogueDefaultOwnerResolver resolver = new DialogueDefaultOwnerResolver();

    @Test
    void theFactoryDefaultTakesTheTurnWhenNothingWasPicked() {
        var resolved = resolver.resolve(
                List.of(
                        owner("assistant", 0, true),
                        owner("thirdParty", 5, false)
                ),
                IaDefaultOwnerSettings.UNCONFIGURED
        );

        assertTrue(resolved.isPresent());
        assertEquals("assistant", resolved.get().participantId(),
                "the out-of-box default is the declared one, not the highest priority module");
    }

    @Test
    void nobodyTakesAnUnclaimedTurnWithoutAFactoryDefault() {
        var resolved = resolver.resolve(
                List.of(owner("thirdParty", 5, false)),
                IaDefaultOwnerSettings.UNCONFIGURED
        );

        assertTrue(resolved.isEmpty());
    }

    @Test
    void anExplicitPickWinsOverTheFactoryDefault() {
        var resolved = resolver.resolve(
                List.of(owner("assistant", 0, true), owner("thirdParty", 5, false)),
                new Settings(false, "thirdParty")
        );

        assertTrue(resolved.isPresent());
        assertEquals("thirdParty", resolved.get().participantId());
    }

    @Test
    void turningItOffWinsOverEverything() {
        var resolved = resolver.resolve(
                List.of(owner("assistant", 0, true)),
                new Settings(true, "assistant")
        );

        assertTrue(resolved.isEmpty());
    }

    @Test
    void aPickThatIsNotRegisteredMeansNobody() {
        var resolved = resolver.resolve(
                List.of(owner("assistant", 0, true)),
                new Settings(false, "uninstalled")
        );

        assertTrue(resolved.isEmpty(), "the player's pick is not silently replaced by another module");
    }

    @Test
    void aPickThatCannotOwnATurnIsIgnored() {
        var resolved = resolver.resolve(
                List.of(descriptor("maid", DialogueClaimProfile.rules(List.of()))),
                new Settings(false, "maid")
        );

        assertTrue(resolved.isEmpty());
    }

    private DialogueParticipantDescriptor owner(String participantId, int priority, boolean factoryDefault) {
        return new DialogueParticipantDescriptor(
                participantId,
                "module." + participantId,
                participantId,
                priority,
                DialogueClaimProfile.DEFAULT_OWNER,
                DialogueTriggerGroup.EMPTY,
                "ROUTE",
                DialogueTurnProcessingPolicy.DEFAULT,
                "",
                DialogueHoldTier.NONE,
                factoryDefault
        );
    }

    private DialogueParticipantDescriptor descriptor(String participantId, DialogueClaimProfile profile) {
        return new DialogueParticipantDescriptor(
                participantId,
                "module." + participantId,
                participantId,
                1,
                profile,
                DialogueTriggerGroup.EMPTY,
                "ROUTE",
                DialogueTurnProcessingPolicy.DEFAULT,
                "tianshu:textures/gui/partner/" + participantId + ".png",
                DialogueHoldTier.TEN_SECONDS,
                false
        );
    }

    private record Settings(boolean disabled, String participantId) implements IaDefaultOwnerSettings {
        @Override
        public boolean isDisabled() {
            return disabled;
        }

        @Override
        public String participantId() {
            return participantId;
        }
    }
}