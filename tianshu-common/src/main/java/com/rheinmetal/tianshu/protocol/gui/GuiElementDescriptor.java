package com.rheinmetal.tianshu.protocol.gui;

import java.util.List;
import java.util.Map;

public record GuiElementDescriptor(
        String elementId,
        GuiElementType type,
        String labelKey,
        String value,
        List<String> lines,
        double min,
        double max,
        double step,
        boolean enabled,
        GuiInteractionState state,
        Map<String, String> actions,
        List<GuiElementDescriptor> children
) {
    public GuiElementDescriptor {
        elementId = requireText(elementId, "elementId");
        type = type == null ? GuiElementType.TEXT : type;
        labelKey = sanitize(labelKey);
        value = sanitize(value);
        lines = lines == null || lines.isEmpty()
                ? List.of()
                : lines.stream().filter(line -> line != null && !line.isBlank()).map(String::trim).toList();
        step = Math.max(0.0D, step);
        state = state == null ? GuiInteractionState.NORMAL : state;
        actions = actions == null || actions.isEmpty() ? Map.of() : Map.copyOf(actions);
        children = children == null || children.isEmpty() ? List.of() : List.copyOf(children);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
