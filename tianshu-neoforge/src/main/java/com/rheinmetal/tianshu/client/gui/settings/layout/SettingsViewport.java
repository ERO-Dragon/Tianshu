package com.rheinmetal.tianshu.client.gui.settings.layout;

public record SettingsViewport(int top, int bottom, int scrollOffset) {
    public int translateY(int y) {
        return y - scrollOffset;
    }

    public boolean intersects(int y, int height) {
        int translated = translateY(y);
        return translated + height >= top && translated <= bottom;
    }

    public int height() {
        return Math.max(0, bottom - top);
    }
}


