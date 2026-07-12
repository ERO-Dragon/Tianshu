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
        assertTrue(content.contains("short complete prompt"));
        assertFalse(content.contains("standard complete prompt"));
        assertFalse(content.contains("full complete prompt"));
    }

    @Test
    void selectsFullSystemProfileWhenTokenBudgetAllowsIt() {
        AXPromptAssemblyBuilder builder = new AXPromptAssemblyBuilder();

        new AXSystemPromptContributor().contribute(buildContext(200), builder);

        String content = builder.build().messages().get(0).content();
        assertTrue(content.contains("full complete prompt"));
    }

    @Test
    void keepsShortestSystemPromptWholeWhenEveryProfileExceedsItsSlot() {
        AXPromptAssemblyBuilder builder = new AXPromptAssemblyBuilder();

        new AXSystemPromptContributor().contribute(buildContext(1), builder);

        assertTrue(builder.build().messages().get(0).content().contains("short complete prompt"));
    }

    private AXPromptBuildContext buildContext(int systemTokenBudget) {
        AXSystemPromptSet prompts = new AXSystemPromptSet(
                "short complete prompt",
                "standard complete prompt with several extra words for reliable behavior",
                "full complete prompt with many extra descriptive words for grounded reliable immersive assistant behavior"
        );
        return new AXPromptBuildContext(
                new AXRequest("request", "hello", ""),
                AXContextSnapshot.empty(),
                new AXContextBudget(systemTokenBudget, 0, 0, 0, 0, 1000, 0, 0),
                AXPromptLanguage.EN_US,
                new AXPromptProfile(AXPromptTask.GENERAL_AX, AXPromptLanguage.EN_US, prompts, List.of("ax_system")),
                new AXPromptTexts(AXPromptLanguage.EN_US, Map.of(
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
