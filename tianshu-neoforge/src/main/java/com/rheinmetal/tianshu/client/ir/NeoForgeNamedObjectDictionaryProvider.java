package com.rheinmetal.tianshu.client.ir;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NeoForgeNamedObjectDictionaryProvider implements com.rheinmetal.tianshu.client.ir.NamedObjectDictionaryProvider {
    private final ClientItemDictionaryBuilder itemBuilder = new ClientItemDictionaryBuilder();
    private final ClientEntityDictionaryBuilder entityBuilder = new ClientEntityDictionaryBuilder();

    @Override
    public Map<String, List<String>> buildDictionary() {
        LinkedHashMap<String, List<String>> dictionary = new LinkedHashMap<>();
        dictionary.putAll(itemBuilder.build());
        dictionary.putAll(entityBuilder.build());
        return dictionary;
    }
}
