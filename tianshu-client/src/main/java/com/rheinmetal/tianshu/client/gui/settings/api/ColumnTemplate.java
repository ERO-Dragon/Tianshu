package com.rheinmetal.tianshu.client.gui.settings.api;

import java.util.function.Consumer;

public interface ColumnTemplate {
    ColumnTemplate column(int index, Consumer<ModuleSettingsPanel> builder);
}
