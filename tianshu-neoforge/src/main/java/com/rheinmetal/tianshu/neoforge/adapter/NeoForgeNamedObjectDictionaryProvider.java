package com.rheinmetal.tianshu.neoforge.adapter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

public final class NeoForgeNamedObjectDictionaryProvider implements com.rheinmetal.tianshu.client.ir.NamedObjectDictionaryProvider {
    private final ClientItemDictionaryBuilder itemBuilder = new ClientItemDictionaryBuilder();
    private final ClientEntityDictionaryBuilder entityBuilder = new ClientEntityDictionaryBuilder();
    private volatile Map<String, List<String>> dictionarySnapshot = Map.of();

    @Override
    public Map<String, List<String>> snapshot() {
        return dictionarySnapshot;
    }

    public void refresh() {
        LinkedHashMap<String, List<String>> dictionary = new LinkedHashMap<>();
        dictionary.putAll(itemBuilder.build());
        dictionary.putAll(entityBuilder.build());
        LinkedHashMap<String, List<String>> immutable = new LinkedHashMap<>();
        dictionary.forEach((key, aliases) -> immutable.put(key, aliases == null ? List.of() : List.copyOf(aliases)));
        dictionarySnapshot = Collections.unmodifiableMap(immutable);
    }
}
