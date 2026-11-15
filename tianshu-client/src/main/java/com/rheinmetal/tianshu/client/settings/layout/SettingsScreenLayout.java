package com.rheinmetal.tianshu.client.settings.layout;

/**
 * Where each part of the settings screen sits, derived from the rectangle it was given.
 *
 * <p>The header height is a named metric rather than a literal: every other spacing on this screen comes from
 * {@link SettingsLayoutMetrics}, and a lone {@code 28} in a method was the one number a layout change would miss.</p>
 */
public record SettingsScreenLayout(int leftX, int rightX, int contentTop, int contentBottom, int leftWidth, int rightWidth) {

    /** Height of the screen title band above both panels. */
    public static final int HEADER_HEIGHT = 28;

    public int panelHeight() {
        return Math.max(0, contentBottom - contentTop);
    }

    public int headerHeight() {
        return HEADER_HEIGHT;
    }

    public int viewportTop() {
        return Math.min(contentBottom, contentTop + headerHeight());
    }

    public int leftListTop() {
        return viewportTop();
    }

    public int leftListHeight() {
        return Math.max(0, contentBottom - leftListTop());
    }

    /** Where the scrolling viewport ends. The list occupies exactly this range, so it ends at the content bottom. */
    public int viewportBottom() {
        return contentBottom;
    }

    public int actionsY() {
        return contentBottom + SettingsLayoutMetrics.GROUP_GAP;
    }

    public boolean containsRightPanel(double mouseX, double mouseY) {
        return mouseX >= rightX && mouseX <= rightX + rightWidth && mouseY >= contentTop && mouseY <= contentBottom;
    }
}


