package com.rheinmetal.tianshu.function.auxilium.module.system;

import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptAssemblyBuilder;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptBuildContext;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptContributor;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptSectionRenderer;

/**
 * Contributes the single, byte-frozen system prompt.
 *
 * <p>There used to be three prompt sizes selected against the remaining token budget. That selection
 * is gone, and so is the three-field data model behind it: the profile now carries exactly one
 * complete system prompt. A shortened prompt saves a few hundred tokens once, while a byte-stable
 * prompt lets the provider cache the whole prefix on every turn; constant bytes win by two orders of
 * magnitude. The context tier therefore scales the surrounding sections only, never this text.
 */
public final class AXSystemPromptContributor implements AXPromptContributor {
    public static final String SECTION_ID = "ax_system";

    @Override
    public String sectionId() {
        return SECTION_ID;
    }

    @Override
    public void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder) {
        String prompt = singleSystemPrompt(context);
        if (prompt.isBlank()) {
            return;
        }
        builder.addContextSection(renderSystemSection(context, prompt));
    }

    /** The one system prompt, byte-identical on every turn. */
    private String singleSystemPrompt(AXPromptBuildContext context) {
        return context.profile().systemPrompt();
    }

    private String renderSystemSection(AXPromptBuildContext context, String content) {
        return AXPromptSectionRenderer.renderContent(context, AXPromptTexts.SECTION_AX_SYSTEM, content == null ? "" : content);
    }
}
