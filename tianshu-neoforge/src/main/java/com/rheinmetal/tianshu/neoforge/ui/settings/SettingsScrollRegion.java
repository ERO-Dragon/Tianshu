package com.rheinmetal.tianshu.neoforge.ui.settings;

import java.util.List;

import net.minecraft.client.gui.components.AbstractWidget;

public record SettingsScrollRegion(String id, int x, int y, int width, int height, int contentHeight, int viewportHeight, List<SettingsDecoration> decorations, List<AbstractWidget> widgets) {
    public SettingsScrollRegion {
        id = id == null ? "" : id;
        decorations = decorations == null ? List.of() : List.copyOf(decorations);
        widgets = widgets == null ? List.of() : List.copyOf(widgets);
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    public boolean canScroll() {
        return contentHeight > viewportHeight;
    }
}
