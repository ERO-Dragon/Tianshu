package com.rheinmetal.tianshu.client.ir;

import com.rheinmetal.tianshu.client.language.ClientLanguagePolicy;
import com.rheinmetal.tianshu.function.ir.core.IRObjectId;
import net.minecraft.core.registries.BuiltInRegistries;
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
            String localizedName = ClientLanguagePolicy.useChineseContent()
                    ? ClientLanguagePolicy.safeTranslatedName(net.minecraft.network.chat.Component.translatable(item.getDescriptionId()).getString())
                    : "";
            String registryAlias = ClientLanguagePolicy.englishName(key);
            
            List<String> aliases = new ArrayList<>();
            if (localizedName != null && !localizedName.isEmpty()) {
                aliases.add(localizedName);
            }
            if (registryAlias != null && !registryAlias.isEmpty() && !registryAlias.equalsIgnoreCase(localizedName)) {
                aliases.add(registryAlias);
            }
            
            if (!aliases.isEmpty()) {
                dictionary.put(IRObjectId.item(realItemId), aliases);
            }
        }
        return dictionary;
    }
}
