package com.rheinmetal.tianshu.neoforge.adapter;

import com.rheinmetal.tianshu.neoforge.adapter.ClientLanguagePolicy;
import com.rheinmetal.tianshu.function.ir.core.IRObjectId;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ClientEntityDictionaryBuilder {

    Map<String, List<String>> build() {
        LinkedHashMap<String, List<String>> dictionary = new LinkedHashMap<>(BuiltInRegistries.ENTITY_TYPE.size());
        for (EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
            if (key == null) {
                continue;
            }

            String entityTypeId = key.toString();
            String localizedName = ClientLanguagePolicy.useChineseContent()
                    ? ClientLanguagePolicy.safeTranslatedName(Component.translatable(entityType.getDescriptionId()).getString())
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
                dictionary.put(IRObjectId.entity(entityTypeId), aliases);
            }
        }
        return dictionary;
    }
}
