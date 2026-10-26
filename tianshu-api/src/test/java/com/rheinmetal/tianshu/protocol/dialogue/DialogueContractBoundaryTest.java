package com.rheinmetal.tianshu.protocol.dialogue;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueVoiceTriggerGroup;
import org.junit.jupiter.api.Test;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.function.ia.contract.DialogueScoringContext;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import com.rheinmetal.tianshu.function.ia.contract.DialogueScoreResponsePayload;
import com.rheinmetal.tianshu.function.ia.contract.IrModuleMatch;
import com.rheinmetal.tianshu.function.ia.contract.IrObjectMatch;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void participantDescriptorRetainsCompatibilityAndNormalizesFallbackScore() {
        DialogueParticipantDescriptor legacy = new DialogueParticipantDescriptor(
                "legacy", "module.legacy", "Legacy", 1,
                DialogueClaimProfile.DISABLED,
                com.rheinmetal.tianshu.protocol.dialogue.model.DialogueVoiceTriggerGroup.EMPTY,
                "LEGACY.ROUTE", DialogueTurnProcessingPolicy.DEFAULT
        );
        DialogueParticipantDescriptor scored = new DialogueParticipantDescriptor(
                "scored", "module.scored", "Scored", 1,
                DialogueClaimProfile.DISABLED,
                com.rheinmetal.tianshu.protocol.dialogue.model.DialogueVoiceTriggerGroup.EMPTY,
                "SCORE.ROUTE", DialogueTurnProcessingPolicy.DEFAULT,
                "SCORE.CAPABILITY", 2.0D
        );

        assertEquals("", legacy.scoreCapability());
        assertEquals(0.0D, legacy.fallbackScore());
        assertEquals("SCORE.CAPABILITY", scored.scoreCapability());
        assertEquals(1.0D, scored.fallbackScore());
    }

    @Test
    void scoringContextContainsOnlyStructuredDialogueContext() {
        DialogueScoringContext context = new DialogueScoringContext(
                "request", "player", "turn",
                List.of(new IrObjectMatch("钻石锄", "minecraft:diamond_hoe", "item", "item.minecraft.diamond_hoe")),
                List.of(new IrModuleMatch("module.maid", "酒狐", "酒狐")),
                DialogueInteractionHints.empty(), DialogueContextSnapshot.empty("player"), 10L, 20L
        );

        List<String> components = Stream.of(context.getClass().getRecordComponents())
                .map(component -> component.getName())
                .toList();
        assertTrue(components.stream().noneMatch(name -> List.of("repairedText", "normalizedText", "prompt", "rawText").contains(name)));
        assertTrue(components.contains("objectMatches"));
        assertTrue(components.contains("moduleMatches"));
        assertEquals(1, context.objectMatches().size());
        assertEquals("minecraft:diamond_hoe", context.objectMatches().get(0).registryId());
        assertEquals(1, context.moduleMatches().size());
    }

    @Test
    void scoreResponseRejectsNonFiniteScore() {
        assertThrows(IllegalArgumentException.class, () -> new DialogueScoreResponsePayload(
                "request", "participant", Double.NaN, 10L, true
        ));
    }
}
