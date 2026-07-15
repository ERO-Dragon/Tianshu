package com.rheinmetal.tianshu.platform;

import com.rheinmetal.tianshu.client.host.ClientTextProvider;
import com.rheinmetal.tianshu.client.ui.UiText;
import net.minecraft.client.resources.language.I18n;

public final class NeoForgeClientTextProvider implements ClientTextProvider {
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
}
