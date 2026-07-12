package com.rheinmetal.tianshu.function.auxilium.module.system;

import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptAssemblyBuilder;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptBuildContext;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptContributor;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptSectionRenderer;

import java.util.OptionalInt;

public final class AXSystemPromptContributor implements AXPromptContributor {
    public static final String SECTION_ID = "ax_system";

    @Override
    public String sectionId() {
        return SECTION_ID;
    }

    @Override
    public void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder) {
        int tokenBudget = context.budget().systemTokenBudget();
        String prompt = selectSystemPrompt(context, tokenBudget);
        if (prompt.isBlank()) {
            return;
        }
        builder.addContextSection(renderSystemSection(context, prompt));
    }

    private String selectSystemPrompt(AXPromptBuildContext context, int tokenBudget) {
        String fallback = "";
        String standard = context.profile().systemPrompts().standardPrompt();
        int index = 0;
        for (String prompt : context.profile().systemPrompts().largestFirst()) {
            if (prompt.isBlank()) {
                index++;
                continue;
            }
            if (fallback.isBlank()) {
                fallback = prompt;
            }
            if (tokenBudget <= 0) {
                return prompt;
            }
            OptionalInt count = countTokens(context, renderSystemSection(context, prompt), "profile." + index);
            if (count.isEmpty()) {
                return standard.isBlank() ? prompt : standard;
            }
            if (count.getAsInt() <= tokenBudget) {
                return prompt;
            }
            index++;
        }
        String shortest = context.profile().systemPrompts().shortPrompt();
        return shortest.isBlank() ? fallback : shortest;
    }

    private OptionalInt countTokens(AXPromptBuildContext context, String text, String suffix) {
        String requestId = context.request() == null ? "ax.system_profile" : context.request().requestKey();
        return context.tokenCounter().countMessageTokens(requestId + "." + suffix, "system", text);
    }

    private String renderSystemSection(AXPromptBuildContext context, String content) {
        return AXPromptSectionRenderer.renderContent(context, AXPromptTexts.SECTION_AX_SYSTEM, content == null ? "" : content);
    }
}
