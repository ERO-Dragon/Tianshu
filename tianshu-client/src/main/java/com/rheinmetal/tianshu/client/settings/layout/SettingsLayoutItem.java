package com.rheinmetal.tianshu.client.settings.layout;

public record SettingsLayoutItem(int contentY, int screenY, int height, boolean visible, int column, int width) {
    public SettingsLayoutItem(int contentY, int screenY, int height, boolean visible) {
        this(contentY, screenY, height, visible, 0, 0);
    }
}


