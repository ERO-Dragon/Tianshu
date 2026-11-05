package com.rheinmetal.tianshu.mc.host;

import com.rheinmetal.tianshu.client.host.ClientTextProvider;
import com.rheinmetal.tianshu.client.api.text.UiText;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;

public final class GameTextProvider implements ClientTextProvider {
    @Override
    public String text(UiText text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (text.composite()) {
            return text.parts().stream().map(this::text).reduce("", String::concat);
        }
        if (!text.translatable()) {
            return text.value();
        }
        return I18n.get(text.value(), text.arguments().toArray());
    }

    @Override
    public String currentLanguage() {
        return net.minecraft.client.Minecraft.getInstance().options.languageCode;
    }

    @Override
    public boolean hasKey(String key) {
        // 26.2 removed I18n.exists; the language table it consulted is what answers now.
        return key != null && !key.isBlank() && Language.getInstance().has(key);
    }
}
