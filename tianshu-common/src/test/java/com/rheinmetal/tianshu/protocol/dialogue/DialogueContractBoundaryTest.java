package com.rheinmetal.tianshu.protocol.dialogue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueContractBoundaryTest {
    private static final List<String> CONSUMER_MODULES = List.of(
            "function/auxilium",
            "function/ir",
            "function/llm"
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
}
