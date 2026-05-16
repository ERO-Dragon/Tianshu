package com.rheinmetal.tianshu.protocol.gui;

import java.util.List;

public record GuiContributionDescriptor(
        String moduleId,
        String pageId,
        String titleKey,
        List<GuiElementDescriptor> elements,
        List<GuiThemeToken> themeTokens,
        long updatedAtMillis
) {
    public GuiContributionDescriptor {
        moduleId = requireText(moduleId, "moduleId");
        pageId = requireText(pageId, "pageId");
        titleKey = titleKey == null || titleKey.isBlank() ? pageId : titleKey.trim();
        elements = elements == null || elements.isEmpty() ? List.of() : List.copyOf(elements);
        themeTokens = themeTokens == null || themeTokens.isEmpty() ? List.of() : List.copyOf(themeTokens);
        if (updatedAtMillis <= 0L) updatedAtMillis = System.currentTimeMillis();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }
}
