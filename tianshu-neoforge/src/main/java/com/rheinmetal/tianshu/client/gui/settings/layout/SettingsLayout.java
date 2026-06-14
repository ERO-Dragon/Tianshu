package com.rheinmetal.tianshu.client.gui.settings.layout;

public final class SettingsLayout {
    private final SettingsViewport viewport;
    private final int originY;
    private int cursorY;
    private SettingsLayoutItem lastRow;

    public SettingsLayout(int originY, SettingsViewport viewport) {
        this.originY = originY;
        this.viewport = viewport;
        this.cursorY = originY;
    }

    public SettingsLayoutItem next(int height) {
        SettingsLayoutItem item = new SettingsLayoutItem(cursorY, viewport.translateY(cursorY), height, viewport.contains(cursorY, height));
        cursorY += height;
        return item;
    }

    public SettingsLayoutItem row() {
        lastRow = next(SettingsLayoutMetrics.ROW_HEIGHT);
        return lastRow;
    }

    public SettingsLayoutItem peekLastRow() {
        return lastRow == null ? row() : lastRow;
    }

    public void gap() {
        cursorY += SettingsLayoutMetrics.GAP;
        lastRow = null;
    }

    public void groupGap() {
        cursorY += SettingsLayoutMetrics.GROUP_GAP;
        lastRow = null;
    }

    public void sectionPadding() {
        cursorY += SettingsLayoutMetrics.SECTION_PADDING;
        lastRow = null;
    }

    public int cursorY() {
        return cursorY;
    }

    public int contentHeight() {
        return Math.max(0, cursorY - originY);
    }
}


