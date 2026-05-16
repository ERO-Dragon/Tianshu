package com.rheinmetal.tianshu.client.gui.settings.registry;

import com.rheinmetal.tianshu.client.gui.settings.model.ModuleSettingsCategory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TianshuSettingsRegistry {
    private final List<ModuleSettingsCategory> categories = new ArrayList<>();

    public void registerCategory(ModuleSettingsCategory category) {
        if (category == null) {
            return;
        }
        categories.removeIf(existing -> existing.moduleId().equals(category.moduleId()));
        categories.add(category);
        categories.sort(Comparator.comparingInt(ModuleSettingsCategory::order).thenComparing(ModuleSettingsCategory::moduleId));
    }

    public List<ModuleSettingsCategory> categories() {
        return List.copyOf(categories);
    }

    public ModuleSettingsCategory find(String moduleId) {
        return categories.stream()
                .filter(category -> category.moduleId().equals(moduleId))
                .findFirst()
                .orElseGet(() -> categories.stream().findFirst().orElse(null));
    }

    public void clear() {
        categories.clear();
    }
}
