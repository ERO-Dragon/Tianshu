package com.rheinmetal.tianshu.dubhe.module.system;

import com.rheinmetal.tianshu.dubhe.DubheRequest;
import com.rheinmetal.tianshu.dubhe.core.context.DubheContextBudget;
import com.rheinmetal.tianshu.dubhe.core.context.DubheContextSnapshot;
import com.rheinmetal.tianshu.dubhe.core.prompt.DubhePromptAssemblyBuilder;
import com.rheinmetal.tianshu.dubhe.core.prompt.DubhePromptBuildContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheSystemPromptContributorTest {
    private static final String SYSTEM_PROMPT =
            "single complete system prompt with many extra descriptive words for grounded reliable immersive assistant behavior";

    @Test
    void usesTheSingleConfiguredSystemPrompt() {
        DubhePromptAssemblyBuilder builder = new DubhePromptAssemblyBuilder();

        new DubheSystemPromptContributor().contribute(buildContext(), builder);

        String content = builder.build().messages().get(0).content();
        assertTrue(content.contains(SYSTEM_PROMPT));
    }

    private DubhePromptBuildContext buildContext() {
        return new DubhePromptBuildContext(
                new DubheRequest("request", "hello", ""),
                DubheContextSnapshot.empty(),
                DubheContextBudget.DEFAULT,
                DubhePromptLanguage.EN_US,
                new DubhePromptProfile(DubhePromptTask.GENERAL_DUBHE, DubhePromptLanguage.EN_US, SYSTEM_PROMPT, List.of("dubhe_system")),
                new DubhePromptTexts(DubhePromptLanguage.EN_US, Map.of(
                        DubhePromptTexts.SECTION_DUBHE_SYSTEM, "<dubhe_system>\n{{content}}\n</dubhe_system>"
                ))
        );
    }
}
