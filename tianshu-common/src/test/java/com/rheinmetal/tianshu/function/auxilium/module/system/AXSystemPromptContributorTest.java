package com.rheinmetal.tianshu.function.auxilium.module.system;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptAssemblyBuilder;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptBuildContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AXSystemPromptContributorTest {
    private static final String SYSTEM_PROMPT =
            "single complete system prompt with many extra descriptive words for grounded reliable immersive assistant behavior";

    @Test
    void usesTheSingleConfiguredSystemPrompt() {
        AXPromptAssemblyBuilder builder = new AXPromptAssemblyBuilder();

        new AXSystemPromptContributor().contribute(buildContext(), builder);

        String content = builder.build().messages().get(0).content();
        assertTrue(content.contains(SYSTEM_PROMPT));
    }

    private AXPromptBuildContext buildContext() {
        return new AXPromptBuildContext(
                new AXRequest("request", "hello", ""),
                AXContextSnapshot.empty(),
                AXContextBudget.DEFAULT,
                AXPromptLanguage.EN_US,
                new AXPromptProfile(AXPromptTask.GENERAL_AX, AXPromptLanguage.EN_US, SYSTEM_PROMPT, List.of("ax_system")),
                new AXPromptTexts(AXPromptLanguage.EN_US, Map.of(
                        AXPromptTexts.SECTION_AX_SYSTEM, "<ax_system>\n{{content}}\n</ax_system>"
                ))
        );
    }
}
