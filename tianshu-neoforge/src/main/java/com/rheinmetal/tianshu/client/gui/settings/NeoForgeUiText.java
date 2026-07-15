package com.rheinmetal.tianshu.client.gui.settings;

import com.rheinmetal.tianshu.client.ui.UiText;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class NeoForgeUiText {
    private NeoForgeUiText() {
    }

    public static Component toComponent(UiText text) {
        if (text == null) {
            return Component.empty();
        }
        if (text.composite()) {
            MutableComponent result = Component.empty();
            for (UiText part : text.parts()) {
                result.append(toComponent(part));
            }
            return result;
        }
        if (!text.translatable()) {
            return Component.literal(text.value());
        }
        Object[] arguments = text.arguments().stream()
                .map(NeoForgeUiText::convertArgument)
                .toArray();
        return Component.translatable(text.value(), arguments);
    }

    public static boolean isEmpty(UiText text) {
        if (text == null) {
            return true;
        }
        if (text.composite()) {
            return text.parts().stream().allMatch(NeoForgeUiText::isEmpty);
        }
        return text.value().isBlank();
    }

    private static Object convertArgument(Object argument) {
        return argument instanceof UiText text ? toComponent(text) : argument;
    }
}
