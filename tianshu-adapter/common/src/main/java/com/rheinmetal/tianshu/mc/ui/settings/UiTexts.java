package com.rheinmetal.tianshu.mc.ui.settings;

import com.rheinmetal.tianshu.client.api.text.UiText;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class UiTexts {
    private UiTexts() {
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
                .map(UiTexts::convertArgument)
                .toArray();
        return Component.translatable(text.value(), arguments);
    }

    public static boolean isEmpty(UiText text) {
        if (text == null) {
            return true;
        }
        if (text.composite()) {
            return text.parts().stream().allMatch(UiTexts::isEmpty);
        }
        return text.value().isBlank();
    }

    private static Object convertArgument(Object argument) {
        return argument instanceof UiText text ? toComponent(text) : argument;
    }
}
