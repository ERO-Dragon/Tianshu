package com.rheinmetal.tianshu.api.tool;

import java.util.List;

/**
 * Optional server-to-client catalogue sink. An adapter that has a packet to reuse registers one per joined player;
 * adapters that do not implement it keep the existing login catalogue plus the 60-second refresh.
 */
@FunctionalInterface
public interface ToolCatalogPush {

    void push(String playerId, List<String> toolIds);
}
