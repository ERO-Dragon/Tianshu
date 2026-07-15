package com.rheinmetal.tianshu.client.gui.settings.model;

import com.rheinmetal.tianshu.client.ui.UiText;

import java.util.Objects;

public final class ModuleSettingsCategory {
    private final String moduleId;
    private final UiText title;
    private final UiText description;
    private final int order;
    private final ModuleSettingsPanelFactory panelFactory;

    private ModuleSettingsCategory(Builder builder) {
        this.moduleId = Objects.requireNonNull(builder.moduleId, "moduleId");
        this.title = Objects.requireNonNull(builder.title, "title");
        this.description = builder.description == null ? UiText.literal("") : builder.description;
        this.order = builder.order;
        this.panelFactory = Objects.requireNonNull(builder.panelFactory, "panelFactory");
    }

    public static Builder builder(String moduleId) {
        return new Builder(moduleId);
    }

    public String moduleId() {
        return moduleId;
    }

    public UiText title() {
        return title;
    }

    public UiText description() {
        return description;
    }

    public int order() {
        return order;
    }

    public ModuleSettingsPanelFactory panelFactory() {
        return panelFactory;
    }

    public static final class Builder {
        private final String moduleId;
        private UiText title;
        private UiText description;
        private int order = 1000;
        private ModuleSettingsPanelFactory panelFactory;

        private Builder(String moduleId) {
            this.moduleId = moduleId;
        }

        public Builder title(UiText title) {
            this.title = title;
            return this;
        }

        public Builder description(UiText description) {
            this.description = description;
            return this;
        }

        public Builder order(int order) {
            this.order = order;
            return this;
        }

        public Builder panel(ModuleSettingsPanelFactory panelFactory) {
            this.panelFactory = panelFactory;
            return this;
        }

        public ModuleSettingsCategory build() {
            return new ModuleSettingsCategory(this);
        }
    }
}
