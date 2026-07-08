package com.rheinmetal.tianshu.function.auxilium.module.system;

import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptAssemblyBuilder;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptBuildContext;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptContributor;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptSectionRenderer;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptTexts;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXSystemProfileContent;

import java.util.Map;
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
        String rendered = selectRenderedProfile(context, tokenBudget);
        if (rendered.isBlank()) {
            return;
        }
        builder.addContextSection(renderSystemSection(context, limitByTokens(context, rendered, tokenBudget)));
    }

    private String selectRenderedProfile(AXPromptBuildContext context, int tokenBudget) {
        String fallback = "";
        String standard = renderProfile(context, context.profile().systemProfiles().standardProfile());
        int index = 0;
        for (AXSystemProfileContent profile : context.profile().systemProfiles().largestFirst()) {
            String rendered = renderProfile(context, profile);
            if (rendered.isBlank()) {
                index++;
                continue;
            }
            if (fallback.isBlank()) {
                fallback = rendered;
            }
            if (tokenBudget <= 0) {
                return rendered;
            }
            OptionalInt count = countTokens(context, renderSystemSection(context, rendered), "profile." + index);
            if (count.isEmpty()) {
                return standard.isBlank() ? rendered : standard;
            }
            if (count.getAsInt() <= tokenBudget) {
                return rendered;
            }
            index++;
        }
        AXSystemProfileContent shortProfile = context.profile().systemProfiles().shortProfile();
        String shortRendered = renderProfile(context, shortProfile);
        return shortRendered.isBlank() ? fallback : limitByTokens(context, shortRendered, tokenBudget);
    }

    private String renderProfile(AXPromptBuildContext context, AXSystemProfileContent profile) {
        StringBuilder text = new StringBuilder();
        AXSystemProfileContent effectiveProfile = profile == null ? AXSystemProfileContent.EMPTY : profile;
        appendParagraph(text, context, context.texts().text(AXPromptTexts.SYSTEM_TITLE_IDENTITY), effectiveProfile.identity());
        appendParagraph(text, context, context.texts().text(AXPromptTexts.SYSTEM_TITLE_BEHAVIOR_RULES), effectiveProfile.behaviorRules());
        appendParagraph(text, context, context.texts().text(AXPromptTexts.SYSTEM_TITLE_SECTION_RULES), context.texts().text(AXPromptTexts.SYSTEM_SECTION_RULES));
        return text.toString();
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

    private String limitByTokens(AXPromptBuildContext context, String value, int maxTokens) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String stripped = value.strip();
        int limit = Math.max(0, maxTokens);
        if (limit <= 0) {
            return stripped;
        }
        OptionalInt fullCount = countTokens(context, renderSystemSection(context, stripped), "short.full");
        if (fullCount.isEmpty() || fullCount.getAsInt() <= limit) {
            return stripped;
        }
        int low = 0;
        int high = stripped.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            String candidate = stripped.substring(0, mid).strip();
            OptionalInt candidateCount = countTokens(context, renderSystemSection(context, candidate), "short.cut." + mid);
            if (candidateCount.isEmpty()) {
                return stripped;
            }
            if (candidateCount.getAsInt() <= limit) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return stripped.substring(0, low).strip();
    }

    private OptionalInt countTokens(AXPromptBuildContext context, String text, String suffix) {
        String requestId = context.request() == null ? "ax.system_profile" : context.request().requestKey();
        return context.tokenCounter().countMessageTokens(requestId + "." + suffix, "system", text);
    }

    private String renderSystemSection(AXPromptBuildContext context, String content) {
        return AXPromptSectionRenderer.renderContent(context, AXPromptTexts.SECTION_AX_SYSTEM, content == null ? "" : content);
    }
}
