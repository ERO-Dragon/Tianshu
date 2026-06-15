package com.rheinmetal.tianshu.client.gui.settings.layout;

public record SettingsViewport(int top, int bottom, int scrollOffset) {
    public int translateY(int y) {
        return y - scrollOffset;
    }

    public boolean intersects(int y, int height) {
        int translated = translateY(y);
        return translated + height >= top && translated <= bottom;
    }

    public boolean contains(int y, int height) {
        int translated = translateY(y);
        return translated >= top && translated + height <= bottom;
    }

    public int height() {
        return Math.max(0, bottom - top);
    }

    public int bottomContentY() {
        return bottom + scrollOffset;
    }
}


