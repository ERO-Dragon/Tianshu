package com.rheinmetal.tianshu.client.ir;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ClientItemDictionaryBuilder {

    Map<String, List<String>> build() {
        LinkedHashMap<String, List<String>> dictionary = new LinkedHashMap<>(BuiltInRegistries.ITEM.size());
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (key == null) continue;
            
            String realItemId = key.toString();
            String localizedName = Component.translatable(item.getDescriptionId()).getString().trim();
            String registryAlias = humanizeRegistryPath(key.getPath());
            
            List<String> aliases = new ArrayList<>();
            // 索引 0：绝对的主语言（中文）
            if (localizedName != null && !localizedName.isEmpty()) {
                aliases.add(localizedName);
            }
            // 索引 1：英文兜底
            if (registryAlias != null && !registryAlias.isEmpty() && !registryAlias.equalsIgnoreCase(localizedName)) {
                aliases.add(registryAlias);
            }
            
            if (!aliases.isEmpty()) {
                dictionary.put(realItemId, aliases);
            }
        }
        return dictionary;
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
