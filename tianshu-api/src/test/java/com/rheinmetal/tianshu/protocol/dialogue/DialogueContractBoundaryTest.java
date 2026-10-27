package com.rheinmetal.tianshu.protocol.dialogue;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimConditionType;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimSource;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueHoldTier;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTriggerGroup;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueContractBoundaryTest {
    private static final List<String> CONSUMER_MODULES = List.of(
            "function/auxilium",
            "function/ir",
            "function/weaving"
    );

    @Test
    void consumerModulesDoNotImportIaDialogueDtos() throws Exception {
        Path sourceRoot = Path.of("src/main/java/com/rheinmetal/tianshu");

        try (Stream<Path> files = Files.walk(sourceRoot)) {
            List<String> violations = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> belongsToConsumerModule(sourceRoot, path))
                    .filter(DialogueContractBoundaryTest::importsIaDialogueDtos)
                    .map(sourceRoot::relativize)
                    .map(Path::toString)
                    .sorted()
                    .toList();

            assertTrue(
                    violations.isEmpty(),
                    "Dialogue DTOs used across modules must live in protocol.dialogue, not function.ia:\n"
                            + String.join("\n", violations)
            );
        }
    }

    private static boolean belongsToConsumerModule(Path sourceRoot, Path path) {
        String relative = sourceRoot.relativize(path).toString().replace('\\', '/');
        return CONSUMER_MODULES.stream().anyMatch(relative::startsWith);
    }

    private static boolean importsIaDialogueDtos(Path path) {
        String code;
        try {
            code = Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }

        return code.contains("com.rheinmetal.tianshu.function.ia.payload.")
                || code.contains("com.rheinmetal.tianshu.function.ia.model.")
                || code.contains("com.rheinmetal.tianshu.function.ia.context.DialogueContextFrame")
                || code.contains("com.rheinmetal.tianshu.function.ia.context.DialogueContextSnapshot")
                || code.contains("com.rheinmetal.tianshu.function.ia.context.DialogueEntityRef")
                || code.contains("com.rheinmetal.tianshu.function.ia.context.DialogueInteractionHints");
    }

    @Test
    void participantDescriptorRequiresIdentityAndNormalizesOptionalText() {
        DialogueParticipantDescriptor descriptor = new DialogueParticipantDescriptor(
                "  maid.default  ", "module.maid", "  酒狐  ", 5,
                DialogueClaimProfile.DISABLED, DialogueTriggerGroup.EMPTY,
                " MAID.DIALOGUE_INPUT ", null
        );

        assertEquals("maid.default", descriptor.participantId());
        assertEquals("module.maid", descriptor.moduleId());
        assertEquals("酒狐", descriptor.displayName());
        assertEquals("MAID.DIALOGUE_INPUT", descriptor.routeCapability());
        assertEquals(DialogueClaimProfile.DISABLED, descriptor.claimProfile());
        assertEquals(DialogueTriggerGroup.EMPTY, descriptor.triggerGroup());
        assertEquals(DialogueTurnProcessingPolicy.DEFAULT, descriptor.turnProcessingPolicy());
        assertEquals("", descriptor.icon());
        assertEquals(DialogueHoldTier.NONE, descriptor.holdTier());
        assertFalse(descriptor.hasPartnerIcon());
    }

    @Test
    void partnerIconAndHoldTierAreOptionalButNormalizedWhenDeclared() {
        DialogueParticipantDescriptor descriptor = new DialogueParticipantDescriptor(
                "maid", "module.maid", "酒狐", 5,
                DialogueClaimProfile.rules(java.util.List.of()), DialogueTriggerGroup.EMPTY,
                "MAID.DIALOGUE_INPUT", DialogueTurnProcessingPolicy.DEFAULT,
                "  tianshu:textures/gui/partner/maid.png  ", DialogueHoldTier.FIVE_SECONDS
        );

        assertEquals("tianshu:textures/gui/partner/maid.png", descriptor.icon());
        assertTrue(descriptor.hasPartnerIcon());
        assertEquals(DialogueHoldTier.FIVE_SECONDS, descriptor.holdTier());
        assertEquals(5_000L, descriptor.holdTier().durationMillis());
        assertTrue(descriptor.holdTier().holdsTurn());
        assertFalse(DialogueHoldTier.NONE.holdsTurn());
    }

    @Test
    void onlyTheTwoSentenceConditionsCountAsUtteranceClaims() {
        assertEquals(DialogueClaimSource.UTTERANCE, DialogueClaimConditionType.WAKE_WORD.source());
        assertEquals(DialogueClaimSource.UTTERANCE, DialogueClaimConditionType.MENTIONED_ENTITY.source());
        for (DialogueClaimConditionType type : DialogueClaimConditionType.values()) {
            if (type == DialogueClaimConditionType.WAKE_WORD || type == DialogueClaimConditionType.MENTIONED_ENTITY) {
                continue;
            }
            assertEquals(DialogueClaimSource.CONTEXT, type.source(), type + " reads the world, not the sentence");
        }
    }
}
