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
 * Guards the design boundaries of the event layer at the source level.
 *
 * <p><strong>Why the wording rule changed.</strong> The layer used to be "wording free" in the strict
 * sense: a producer rendered its own sentence and put it on the descriptor, so a language key or a
 * sentence inside this package was a deviation. The contract is now the opposite — a producer submits
 * <em>semantics</em> ({@link AXEventText}: a key plus arguments) and the receiving side renders once
 * through an injected provider. Holding a key here is therefore correct; what must never come back is
 * <em>rendering</em> inside this package. The rules below were rewritten to that boundary, not relaxed:
 * keys are allowed, every marker of "render it here" is not.
 *
 * <p>The second boundary is unchanged: the layer may not know a built-in producer by name. Unit tests can
 * only observe behaviour, so this test also reads the package sources and fails on the literals that would
 * reintroduce either deviation.
 */
class AXEventLayerBoundaryTest {
    private static final Path EVENT_SOURCES =
            Path.of("src/main/java/com/rheinmetal/tianshu/function/auxilium/event");
    private static final Pattern HAN_TEXT = Pattern.compile("[\\u4e00-\\u9fff]");

    /**
     * Markers of rendering. A key and its arguments are data; turning them into a sentence is the provider's
     * job, and it happens on the platform side where the language files are addressed.
     */
    private static final List<String> FORBIDDEN_LITERALS = List.of(
            "net.minecraft",
            "net.neoforged",
            "org.lwjgl",
            "Component.translatable",
            "I18n.",
            "String.format(",
            "String.join(",
            "MessageFormat",
            "ResourceBundle",
            "getString("
    );

    /**
     * The language door itself. The layer knows {@link AXEventText} and an injected
     * {@code Function<AXEventText, String>}; depending on the provider type would let it resolve text on
     * its own, which is the deviation this rule exists to catch.
     */
    private static final String FORBIDDEN_LANGUAGE_DOOR = "AXGameEventTextProvider";

    private static final List<String> FORBIDDEN_SOURCE_BRANCHES = List.of(
            "sourceId.equals",
            "equals(sourceId",
            "switch (descriptor.sourceId",
            "switch (sourceId"
    );

    @Test
    void theLayerHoldsNoNaturalLanguageAndNeverRenders() throws Exception {
        for (Path file : sources()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            assertFalse(HAN_TEXT.matcher(source).find(), () -> "natural language in " + file);
            for (String literal : FORBIDDEN_LITERALS) {
                assertFalse(source.contains(literal), () -> "rendering marker " + literal + " in " + file);
            }
            assertFalse(source.contains(FORBIDDEN_LANGUAGE_DOOR),
                    () -> "the event layer must not depend on the language door itself: " + file);
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

    /**
     * The semantic handle exists and carries no sentence.
     *
     * <p>This replaces the old "the key handle is gone" assertion, whose premise no longer holds: the handle
     * came back with the opposite meaning. What is still worth pinning is that it stays a <em>descriptor</em>
     * — a key and arguments — and never grows a rendered-text accessor, because that is how wording would
     * leak back into the layer one field at a time.
     */
    @Test
    void theLanguageKeyHandleExistsAndCarriesNoRenderedSentence() throws Exception {
        Path handle = EVENT_SOURCES.resolve("AXEventText.java");
        assertTrue(Files.exists(handle), "producers submit key plus arguments, so the handle must exist");
        assertFalse(Files.exists(EVENT_SOURCES.resolve("AXEventAggregateForm.java")),
                "the retired aggregate form must not come back");

        String source = Files.readString(handle, StandardCharsets.UTF_8);
        assertTrue(source.contains("record AXEventText("), "the handle stays a value type");
        assertFalse(source.contains("String sentence"), "the handle must not carry a rendered sentence");
        assertFalse(source.contains("String text("), "the handle must not expose a rendered text accessor");
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
