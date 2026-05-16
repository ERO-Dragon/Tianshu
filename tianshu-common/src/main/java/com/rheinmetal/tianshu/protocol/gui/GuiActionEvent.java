package com.rheinmetal.tianshu.protocol.gui;

import java.util.Map;

public record GuiActionEvent(
        String moduleId,
        String pageId,
        String elementId,
        GuiActionType actionType,
        String value,
        Map<String, String> attributes,
        long timestampMillis
) {
    public GuiActionEvent {
        moduleId = requireText(moduleId, "moduleId");
        pageId = requireText(pageId, "pageId");
        elementId = requireText(elementId, "elementId");
        actionType = actionType == null ? GuiActionType.CLICK : actionType;
        value = value == null ? "" : value.trim();
        attributes = attributes == null || attributes.isEmpty() ? Map.of() : Map.copyOf(attributes);
        if (timestampMillis <= 0L) timestampMillis = System.currentTimeMillis();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }
}
