package com.rheinmetal.tianshu.dubhe.dialogue.system;

import com.rheinmetal.tianshu.dubhe.dialogue.prompt.DubhePromptAssemblyBuilder;
import com.rheinmetal.tianshu.dubhe.dialogue.prompt.DubhePromptBuildContext;
import com.rheinmetal.tianshu.dubhe.dialogue.prompt.DubhePromptContributor;
import com.rheinmetal.tianshu.dubhe.dialogue.prompt.DubhePromptSectionRenderer;

/**
 * Contributes the single, byte-frozen system prompt.
 *
 * <p>There used to be three prompt sizes selected against the remaining token budget. That selection
 * is gone, and so is the three-field data model behind it: the profile now carries exactly one
 * complete system prompt. A shortened prompt saves a few hundred tokens once, while a byte-stable
 * prompt lets the provider cache the whole prefix on every turn; constant bytes win by two orders of
 * magnitude. The context tier therefore scales the surrounding sections only, never this text.
 */
public final class DubheSystemPromptContributor implements DubhePromptContributor {
    public static final String SECTION_ID = "system_prompt";

    @Override
    public String sectionId() {
        return SECTION_ID;
    }

    @Override
    public void contribute(DubhePromptBuildContext context, DubhePromptAssemblyBuilder builder) {
        String prompt = singleSystemPrompt(context);
        if (prompt.isBlank()) {
            return;
        }
        builder.addContextSection(renderSystemSection(context, prompt));
    }

    /** The one system prompt, byte-identical on every turn. */
    private String singleSystemPrompt(DubhePromptBuildContext context) {
        return context.profile().systemPrompt();
    }

    private String renderSystemSection(DubhePromptBuildContext context, String content) {
        return DubhePromptSectionRenderer.renderContent(context, DubhePromptTexts.SECTION_SYSTEM_PROMPT, content == null ? "" : content);
    }
}
