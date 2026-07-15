package com.rheinmetal.tianshu.client.gui.settings.api;

import com.rheinmetal.tianshu.client.ui.UiText;

import java.util.List;

public record SettingsListCard(UiText title, UiText status, List<UiText> details, List<UiText> badges) {
    public SettingsListCard {
        title = title == null ? UiText.literal("") : title;
        status = status == null ? UiText.literal("") : status;
        details = details == null ? List.of() : List.copyOf(details);
        badges = badges == null ? List.of() : List.copyOf(badges);
    }

    public static SettingsListCard text(UiText text) {
        return new SettingsListCard(text, UiText.literal(""), List.of(), List.of());
    }
}
