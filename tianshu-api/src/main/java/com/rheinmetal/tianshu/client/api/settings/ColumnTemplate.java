package com.rheinmetal.tianshu.client.api.settings;

import java.util.function.Consumer;

public interface ColumnTemplate {
    ColumnTemplate column(int index, Consumer<ModuleSettingsPanel> builder);
}
