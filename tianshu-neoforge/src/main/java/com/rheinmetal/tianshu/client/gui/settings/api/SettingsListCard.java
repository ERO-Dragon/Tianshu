package com.rheinmetal.tianshu.client.gui.settings.api;

import net.minecraft.network.chat.Component;

import java.util.List;

public record SettingsListCard(Component title, Component status, List<Component> details, List<Component> badges) {
    public SettingsListCard {
        title = title == null ? Component.empty() : title;
        status = status == null ? Component.empty() : status;
        details = details == null ? List.of() : List.copyOf(details);
        badges = badges == null ? List.of() : List.copyOf(badges);
    }

    public static SettingsListCard text(Component text) {
        return new SettingsListCard(text, Component.empty(), List.of(), List.of());
    }
}
