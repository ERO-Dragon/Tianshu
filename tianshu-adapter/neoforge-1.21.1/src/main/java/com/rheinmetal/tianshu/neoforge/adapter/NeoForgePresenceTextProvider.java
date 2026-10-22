package com.rheinmetal.tianshu.neoforge.adapter;

import com.rheinmetal.tianshu.client.presence.PresenceTextProvider;
import net.minecraft.client.resources.language.I18n;

public final class NeoForgePresenceTextProvider implements PresenceTextProvider {
    @Override
    public boolean exists(String key) {
        return key != null && !key.isBlank() && I18n.exists(key);
    }

    @Override
    public String text(String key, Object... args) {
        if (key == null || key.isBlank()) {
            return "";
        }
        return exists(key) ? I18n.get(key, args) : PresenceTextProvider.NOOP.text(key, args);
    }
}
