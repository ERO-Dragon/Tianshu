package com.rheinmetal.tianshu.client.gui.settings.render;

import java.util.List;

public record SettingsRenderResult(int contentHeight, List<SettingsDecoration> decorations) {
    public SettingsRenderResult {
        decorations = decorations == null ? List.of() : List.copyOf(decorations);
    }
}
