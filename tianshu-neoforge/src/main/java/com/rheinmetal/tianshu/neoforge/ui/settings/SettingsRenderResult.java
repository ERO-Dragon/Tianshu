package com.rheinmetal.tianshu.neoforge.ui.settings;

import java.util.List;

public record SettingsRenderResult(int contentHeight, List<SettingsDecoration> decorations, List<SettingsScrollRegion> scrollRegions) {
    public SettingsRenderResult(int contentHeight, List<SettingsDecoration> decorations) {
        this(contentHeight, decorations, List.of());
    }

    public SettingsRenderResult {
        decorations = decorations == null ? List.of() : List.copyOf(decorations);
        scrollRegions = scrollRegions == null ? List.of() : List.copyOf(scrollRegions);
    }
}
