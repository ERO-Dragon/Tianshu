package com.rheinmetal.tianshu.function.auxilium.module.system;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptAssemblyBuilder;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptBuildContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXSystemPromptContributorTest {
    @Test
    void selectsShortSystemProfileWhenTokenBudgetIsSmall() {
        AXPromptAssemblyBuilder builder = new AXPromptAssemblyBuilder();

        new AXSystemPromptContributor().contribute(buildContext(8), builder);

        String content = builder.build().messages().get(0).content();
        assertTrue(content.contains("short identity"));
        assertTrue(content.contains("brief rule"));
        assertFalse(content.contains("standard identity"));
        assertFalse(content.contains("full identity"));
    }

    @Test
    void selectsFullSystemProfileWhenTokenBudgetAllowsIt() {
        AXPromptAssemblyBuilder builder = new AXPromptAssemblyBuilder();

        new AXSystemPromptContributor().contribute(buildContext(200), builder);

        String content = builder.build().messages().get(0).content();
        assertTrue(content.contains("full identity"));
        assertTrue(content.contains("full rule"));
    }

    private AXPromptBuildContext buildContext(int systemTokenBudget) {
        AXSystemProfileSet profiles = new AXSystemProfileSet(
                new AXSystemProfileContent("short identity", "brief rule"),
                new AXSystemProfileContent("standard identity with several extra words", "standard rule with several extra words"),
                new AXSystemProfileContent("full identity with many extra descriptive words for the assistant profile", "full rule with many extra policy words for grounded reliable immersive assistant behavior")
        );
        return new AXPromptBuildContext(
                new AXRequest("request", "hello", ""),
                AXContextSnapshot.empty(),
                new AXContextBudget(systemTokenBudget, 0, 0, 0, 0, 1000, 0, 0),
                AXPromptLanguage.EN_US,
                new AXPromptProfile(AXPromptTask.GENERAL_AX, AXPromptLanguage.EN_US, profiles, List.of("ax_system")),
                new AXPromptTexts(AXPromptLanguage.EN_US, Map.of(
                        AXPromptTexts.SYSTEM_TITLE_IDENTITY, "Identity",
                        AXPromptTexts.SYSTEM_TITLE_BEHAVIOR_RULES, "Rules",
                        AXPromptTexts.SYSTEM_TITLE_SECTION_RULES, "Section rules",
                        AXPromptTexts.SYSTEM_SECTION_RULES, "",
                        AXPromptTexts.SYSTEM_PARAGRAPH, "{{title}}\n{{content}}",
                        AXPromptTexts.SECTION_AX_SYSTEM, "<ax_system>\n{{content}}\n</ax_system>"
                )),
                (requestId, role, content) -> java.util.OptionalInt.of(countWords(content))
        );
    }

    private int countWords(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return content.trim().split("\\s+").length;
    }
}
