package com.rheinmetal.tianshu.neoforge.adapter;

import com.rheinmetal.tianshu.client.event.AXGameEventTextProvider;
import net.minecraft.client.resources.language.I18n;

/**
 * Resolves the game event producer's language keys through the client language table.
 *
 * <p>A missing key resolves to an empty string instead of the key itself, which is what lets the
 * producer fall back to a plain value and keeps an unresolved key out of the event history.
 */
public final class NeoForgeGameEventTextProvider implements AXGameEventTextProvider {
    @Override
    public boolean exists(String key) {
        return key != null && !key.isBlank() && I18n.exists(key);
    }

    @Override
    public String text(String key, Object... args) {
        if (!exists(key)) {
            return "";
        }
        return I18n.get(key, args);
    }
}
