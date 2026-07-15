package com.rheinmetal.tianshu.client.ir;

import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface NamedObjectDictionaryProvider {
    Map<String, List<String>> buildDictionary();
}
