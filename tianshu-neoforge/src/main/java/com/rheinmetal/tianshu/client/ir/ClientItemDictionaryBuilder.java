package com.rheinmetal.tianshu.client.ir;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Map;

final class ClientItemDictionaryBuilder {
    Map<String, String> build() {
        LinkedHashMap<String, String> dictionary = new LinkedHashMap<>(BuiltInRegistries.ITEM.size());
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (key == null) {
                continue;
            }
            String realItemId = key.toString();
            String localizedName = Component.translatable(item.getDescriptionId()).getString().trim();
            String registryAlias = humanizeRegistryPath(key.getPath());
            dictionary.put(realItemId, mergeAliases(localizedName, registryAlias));
        }
        return dictionary;
    }

    private String mergeAliases(String localizedName, String registryAlias) {
        String primary = localizedName == null ? "" : localizedName.trim();
        String alias = registryAlias == null ? "" : registryAlias.trim();
        if (primary.isEmpty()) {
            return alias;
        }
        if (alias.isEmpty() || primary.equalsIgnoreCase(alias)) {
            return primary;
        }
        return primary + ' ' + alias;
    }

    private String humanizeRegistryPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(path.length());
        boolean previousWasSeparator = true;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '_' || c == '/' || c == ':' || c == '-') {
                if (!previousWasSeparator) {
                    builder.append(' ');
                }
                previousWasSeparator = true;
                continue;
            }
            builder.append(c);
            previousWasSeparator = false;
        }
        return builder.toString().trim();
    }
}
