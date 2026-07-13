package com.rheinmetal.tianshu.function.ir.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandParserCleanBoundaryTest {
    @Test
    void commandParserDoesNotWriteRelativeDebugFilesOrPrintStackTrace() throws Exception {
        String code = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/function/ir/core/CommandParser.java"),
                StandardCharsets.UTF_8
        );

        assertFalse(code.contains("logs"));
        assertFalse(code.contains("ir_debug.txt"));
        assertFalse(code.contains("printStackTrace()"));
    }

    @Test
    void commandParserDoesNotExposeMutableScoringWeights() {
        Set<String> mutablePublicStaticFields = Arrays.stream(CommandParser.class.getFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> !Modifier.isFinal(field.getModifiers()))
                .map(field -> field.getName())
                .collect(Collectors.toSet());

        assertFalse(mutablePublicStaticFields.contains("PRIMARY_WEIGHT"), mutablePublicStaticFields.toString());
        assertFalse(mutablePublicStaticFields.contains("FALLBACK_WEIGHT"), mutablePublicStaticFields.toString());
    }

    @Test
    void commandParserDelegatesCandidateRankingAndTextRepairToDedicatedCollaborators() {
        Set<String> collaboratorTypes = Arrays.stream(CommandParser.class.getDeclaredFields())
                .map(field -> field.getType().getSimpleName())
                .collect(Collectors.toSet());

        assertTrue(collaboratorTypes.contains("CommandCandidateRanker"), collaboratorTypes.toString());
        assertTrue(collaboratorTypes.contains("CommandTextRepairer"), collaboratorTypes.toString());
    }
}
