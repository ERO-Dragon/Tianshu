package com.rheinmetal.tianshu.function.auxilium.event;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the two design boundaries of the event layer at the source level.
 *
 * <p>The layer has to stay wording free and producer agnostic: it may neither hold natural language
 * nor know a built-in producer by name. Unit tests can only observe behaviour, so this test also reads
 * the package sources and fails on the literals that would reintroduce either deviation.
 */
class AXEventLayerBoundaryTest {
    private static final Path EVENT_SOURCES =
            Path.of("src/main/java/com/rheinmetal/tianshu/function/auxilium/event");
    private static final Pattern HAN_TEXT = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final List<String> FORBIDDEN_LITERALS = List.of(
            "net.minecraft",
            "net.neoforged",
            "org.lwjgl",
            "Component.translatable",
            "I18n.",
            "String.format(",
            "\"tianshu:"
    );
    private static final List<String> FORBIDDEN_SOURCE_BRANCHES = List.of(
            "sourceId.equals",
            "equals(sourceId",
            "switch (descriptor.sourceId",
            "switch (sourceId"
    );

    @Test
    void theLayerHoldsNoNaturalLanguageAndNoRenderingDependency() throws Exception {
        for (Path file : sources()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            assertFalse(HAN_TEXT.matcher(source).find(), () -> "natural language in " + file);
            for (String literal : FORBIDDEN_LITERALS) {
                assertFalse(source.contains(literal), () -> "forbidden literal " + literal + " in " + file);
            }
        }
    }

    @Test
    void theLayerNeverBranchesOnTheSourceOfAnEvent() throws Exception {
        for (Path file : sources()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            for (String branch : FORBIDDEN_SOURCE_BRANCHES) {
                assertFalse(source.contains(branch), () -> "source branch " + branch + " in " + file);
            }
        }
    }

    @Test
    void theOldLanguageKeyHandleIsGone() {
        assertFalse(Files.exists(EVENT_SOURCES.resolve("AXEventText.java")));
        assertFalse(Files.exists(EVENT_SOURCES.resolve("AXEventAggregateForm.java")));
    }

    private static List<Path> sources() throws Exception {
        assertTrue(Files.isDirectory(EVENT_SOURCES), "event package sources must be readable from " + EVENT_SOURCES);
        try (var files = Files.walk(EVENT_SOURCES)) {
            List<Path> javaFiles = new ArrayList<>(
                    files.filter(path -> path.toString().endsWith(".java")).toList());
            assertFalse(javaFiles.isEmpty(), "no event sources found");
            return javaFiles;
        }
    }
}
