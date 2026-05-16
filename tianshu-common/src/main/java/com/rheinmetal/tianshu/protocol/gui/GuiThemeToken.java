package com.rheinmetal.tianshu.protocol.gui;

public record GuiThemeToken(
        String tokenId,
        int color,
        int spacing,
        int borderRadius
) {
    public GuiThemeToken {
        tokenId = tokenId == null || tokenId.isBlank() ? "default" : tokenId.trim();
        spacing = Math.max(0, spacing);
        borderRadius = Math.max(0, borderRadius);
    }
}
