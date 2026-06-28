package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptTexts;

import java.util.Map;

public final class AXSystemPromptContributor implements AXPromptContributor {
    public static final String SECTION_ID = "ax_system";

    @Override
    public String sectionId() {
        return SECTION_ID;
    }

    @Override
    public void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder) {
        StringBuilder text = new StringBuilder();
        appendParagraph(text, context, context.texts().text(AXPromptTexts.SYSTEM_TITLE_IDENTITY), context.profile().identity());
        appendParagraph(text, context, context.texts().text(AXPromptTexts.SYSTEM_TITLE_BEHAVIOR_RULES), context.profile().behaviorRules());
        appendParagraph(text, context, context.texts().text(AXPromptTexts.SYSTEM_TITLE_SECTION_RULES), context.texts().text(AXPromptTexts.SYSTEM_SECTION_RULES));
        builder.addSystemMessage(AXPromptSectionRenderer.renderContent(context, AXPromptTexts.SECTION_AX_SYSTEM, text.toString()));
    }

    private void appendParagraph(StringBuilder builder, AXPromptBuildContext context, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append(AXPromptSectionRenderer.render(context, AXPromptTexts.SYSTEM_PARAGRAPH, Map.of(
                "title", title,
                "content", content
        )));
    }
}
