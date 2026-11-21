package com.rheinmetal.tianshu.dubhe.shared.producer;

import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.client.host.ClientTextProvider;

/**
 * Dubhe's view of the game's language table.
 *
 * <p>The platform already exposes one text port, so this module adapts it instead of asking the
 * platform to implement an dubhe interface. The kernel stays free of dubhe types, and a missing
 * key keeps rendering as empty rather than as the key itself.</p>
 */
public final class ModGameEventTexts implements DubheGameEventTextProvider {

    private final ClientTextProvider texts;

    public ModGameEventTexts(ClientTextProvider texts) {
        this.texts = texts == null ? ClientTextProvider.NOOP : texts;
    }

    @Override
    public boolean exists(String key) {
        return key != null && !key.isBlank() && texts.hasKey(key);
    }

    @Override
    public String text(String key, Object... args) {
        if (!exists(key)) {
            return "";
        }
        return texts.text(UiText.key(key, args));
    }
}
