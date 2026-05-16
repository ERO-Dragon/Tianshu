package com.rheinmetal.tianshu.client.gui.settings.layout;

public record SettingsScreenLayout(int leftX, int rightX, int contentTop, int contentBottom, int leftWidth, int rightWidth) {
    public int panelHeight() {
        return Math.max(0, contentBottom - contentTop);
    }

    public int viewportTop() {
        return Math.min(contentBottom, contentTop + 38);
    }

    public int viewportBottom() {
        return Math.max(viewportTop(), contentBottom - 8);
    }

    public int actionsY() {
        return contentBottom + 8;
    }

    public boolean containsRightPanel(double mouseX, double mouseY) {
        return mouseX >= rightX && mouseX <= rightX + rightWidth && mouseY >= contentTop && mouseY <= contentBottom;
    }
}


