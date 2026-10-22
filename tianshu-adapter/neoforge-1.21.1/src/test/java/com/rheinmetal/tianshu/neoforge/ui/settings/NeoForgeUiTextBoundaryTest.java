package com.rheinmetal.tianshu.neoforge.ui.settings;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NeoForgeUiTextBoundaryTest {
    @Test
    void adapterPreservesCompositeOrderAndNestedTranslationArguments() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/ui/settings/NeoForgeUiText.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("for (UiText part : text.parts())"));
        assertTrue(source.contains("result.append(toComponent(part))"));
        assertTrue(source.contains("argument instanceof UiText text ? toComponent(text) : argument"));
        assertTrue(source.contains("Component.translatable(text.value(), arguments)"));
    }
}
