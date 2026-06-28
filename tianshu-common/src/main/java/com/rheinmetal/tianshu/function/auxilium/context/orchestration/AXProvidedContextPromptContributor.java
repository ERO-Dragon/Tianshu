package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptTexts;

public final class AXProvidedContextPromptContributor implements AXPromptContributor {
    public static final String SECTION_ID = "provided_context";

    @Override
    public String sectionId() {
        return SECTION_ID;
    }

    @Override
    public void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder) {
        if (context.context() == null || context.context().providedContext().isBlank()) {
            return;
        }
        builder.addSystemMessage(AXPromptSectionRenderer.renderContent(
                context,
                AXPromptTexts.SECTION_PROVIDED_CONTEXT,
                context.context().providedContext()
        ));
    }
}
