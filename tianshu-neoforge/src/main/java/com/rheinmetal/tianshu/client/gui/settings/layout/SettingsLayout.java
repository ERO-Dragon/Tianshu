package com.rheinmetal.tianshu.client.gui.settings.layout;

public final class SettingsLayout {
    private final SettingsViewport viewport;
    private final int originY;
    private int cursorY;

    public SettingsLayout(int originY, SettingsViewport viewport) {
        this.originY = originY;
        this.viewport = viewport;
        this.cursorY = originY;
    }

    public SettingsLayoutItem next(int height) {
        SettingsLayoutItem item = new SettingsLayoutItem(cursorY, viewport.translateY(cursorY), height, viewport.intersects(cursorY, height));
        cursorY += height;
        return item;
    }

    public SettingsLayoutItem row() {
        return next(SettingsLayoutMetrics.ROW_HEIGHT);
    }

    public void gap() {
        cursorY += SettingsLayoutMetrics.GAP;
    }

    public void groupGap() {
        cursorY += SettingsLayoutMetrics.GROUP_GAP;
    }

    public int contentHeight() {
        return Math.max(0, cursorY - originY);
    }
}


