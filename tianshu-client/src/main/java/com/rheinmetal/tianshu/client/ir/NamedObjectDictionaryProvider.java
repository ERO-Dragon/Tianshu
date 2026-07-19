package com.rheinmetal.tianshu.client.ir;

import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface NamedObjectDictionaryProvider {
    /**
     * Returns the latest immutable platform snapshot. Implementations must not
     * access platform objects while this method is called by the index worker.
     */
    Map<String, List<String>> snapshot();
}
