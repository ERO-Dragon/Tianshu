package com.rheinmetal.tianshu.neoforge.adapter;

import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

public final class ClientLanguagePolicy {
    private ClientLanguagePolicy() {
    }

    public static String captureCurrentLanguageCode() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.options != null && minecraft.options.languageCode != null && !minecraft.options.languageCode.isBlank()) {
                return minecraft.options.languageCode;
            }
            if (minecraft != null && minecraft.getLanguageManager() != null) {
                String selected = minecraft.getLanguageManager().getSelected();
                if (selected != null && !selected.isBlank()) {
                    return selected;
                }
            }
        } catch (Exception ignored) {
        }
        return AXPromptLanguage.EN_US.code();
    }

    public static boolean useChineseContent(String languageCode) {
        return AXPromptLanguage.fromCode(languageCode) == AXPromptLanguage.ZH_CN;
    }

    public static String itemDisplayName(ItemStack stack, ResourceLocation itemId, String languageCode) {
        if (stack != null && useChineseContent(languageCode)) {
            String translated = stack.getHoverName().getString();
            String safe = safeTranslatedName(translated);
            if (!safe.isBlank()) {
                return safe;
            }
        }
        return englishName(itemId);
    }

    public static String registryDisplayName(ResourceLocation id, String translationCategory, String languageCode) {
        if (id == null) {
            return "unknown";
        }
        if (useChineseContent(languageCode) && translationCategory != null && !translationCategory.isBlank()) {
            String translated = Component.translatable(id.toLanguageKey(translationCategory)).getString();
            String safe = safeTranslatedName(translated);
            if (!safe.isBlank()) {
                return safe;
            }
        }
        return englishName(id);
    }

    public static String effectDisplayName(ResourceLocation id, String descriptionId, String languageCode) {
        if (useChineseContent(languageCode) && descriptionId != null && !descriptionId.isBlank()) {
            String translated = Component.translatable(descriptionId).getString();
            String safe = safeTranslatedName(translated);
            if (!safe.isBlank()) {
                return safe;
            }
        }
        return englishName(id);
    }

    public static String englishName(ResourceLocation id) {
        if (id == null) {
            return "unknown";
        }
        return humanizeIdentifier(id.getPath());
    }

    public static String humanizeIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.trim();
        int separator = normalized.indexOf(':');
        if (separator >= 0 && separator < normalized.length() - 1) {
            normalized = normalized.substring(separator + 1);
        }
        String[] parts = normalized.replace('-', '_').replace('/', '_').split("_");
        StringBuilder builder = new StringBuilder(normalized.length());
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return builder.isEmpty() ? "unknown" : builder.toString();
    }

    public static String safeTranslatedName(String translated) {
        if (translated == null || translated.isBlank()) {
            return "";
        }
        String trimmed = translated.trim();
        if (trimmed.contains(".") && (trimmed.startsWith("item.") || trimmed.startsWith("block.") || trimmed.startsWith("entity.") || trimmed.startsWith("enchantment.") || trimmed.startsWith("effect.") || trimmed.startsWith("biome.") || trimmed.startsWith("dimension."))) {
            return "";
        }
        return trimmed;
    }
}
