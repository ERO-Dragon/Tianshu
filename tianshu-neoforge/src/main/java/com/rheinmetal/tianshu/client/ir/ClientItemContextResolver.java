package com.rheinmetal.tianshu.client.ir;

import com.rheinmetal.tianshu.function.ir.core.IRBaseUtils;
import com.rheinmetal.tianshu.function.ir.core.IRObjectId;
import com.rheinmetal.tianshu.function.ir.core.ItemContextProvider;
import com.rheinmetal.tianshu.function.ir.enhance.IrContextHint;

import java.util.HashSet;
import java.util.Set;

final class ClientItemContextResolver {
    private ClientItemContextResolver() {
    }

    static ItemContextProvider from(IrContextHint hint) {
        Set<Integer> contextIds = new HashSet<>(32);
        IrContextHint effective = hint == null ? IrContextHint.empty() : hint;
        effective.itemIds().forEach(itemId -> addItemId(itemId, contextIds));
        return () -> contextIds;
    }

    private static void addItemId(String itemId, Set<Integer> contextIds) {
        if (itemId == null || itemId.isBlank()) {
            return;
        }
        String normalized = itemId.trim();
        Integer internalId = IRBaseUtils.forwardLookupMap.get(IRObjectId.item(normalized));
        if (internalId == null) {
            internalId = IRBaseUtils.forwardLookupMap.get(normalized);
        }
        if (internalId != null) {
            contextIds.add(internalId);
        }
    }
}
