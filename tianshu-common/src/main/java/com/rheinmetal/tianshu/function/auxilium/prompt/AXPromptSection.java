package com.rheinmetal.tianshu.function.auxilium.prompt;

public record AXPromptSection(
        String key,
        String title,
        String content,
        int priority
) {
    public AXPromptSection {
        key = key == null || key.isBlank() ? "section.unknown" : key.trim();
        title = title == null || title.isBlank() ? key : title.trim();
        content = content == null ? "" : content.trim();
        priority = Math.max(0, Math.min(100, priority));
    }

    public boolean isEmpty() {
        return content.isBlank();
    }
}
