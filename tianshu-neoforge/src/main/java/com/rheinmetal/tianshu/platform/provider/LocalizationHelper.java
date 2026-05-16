package com.rheinmetal.tianshu.platform.provider;

final class LocalizationHelper {

    private LocalizationHelper() {}

    static String safeGetDisplayName(String translatedStr) {
        if (translatedStr != null && translatedStr.contains(".") &&
                (translatedStr.startsWith("item.") || translatedStr.startsWith("block.") ||
                        translatedStr.startsWith("entity.") || translatedStr.startsWith("enchantment.") ||
                        translatedStr.startsWith("effect.") || translatedStr.startsWith("biome.") ||
                        translatedStr.startsWith("dimension."))) {
            String[] parts = translatedStr.split("\\.");
            String rawName = parts[parts.length - 1];
            return rawName.replace("_", " ");
        }
        return translatedStr;
    }
}
