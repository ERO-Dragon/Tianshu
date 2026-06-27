package com.rheinmetal.tianshu.client.ir;

import com.rheinmetal.tianshu.client.presence.PresenceClientHooks;
import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceInventoryItem;
import com.rheinmetal.tianshu.function.ir.core.IRBaseUtils;
import com.rheinmetal.tianshu.function.ir.core.IRObjectId;
import com.rheinmetal.tianshu.function.ir.core.ItemContextProvider;

import java.util.HashSet;
import java.util.Set;

final class ClientItemContextCollector implements ItemContextProvider {
    @Override
    public Set<Integer> getContextInternalIds() {
        PresenceContextSnapshot snapshot = PresenceClientHooks.contextSnapshot();
        HashSet<Integer> contextIds = new HashSet<>(64);
        addItemId(snapshot.heldItemId(), contextIds);
        snapshot.equippedItemIds().forEach(itemId -> addItemId(itemId, contextIds));
        snapshot.inventoryItems().stream()
                .map(PresenceInventoryItem::itemId)
                .forEach(itemId -> addItemId(itemId, contextIds));

        return contextIds;
    }

    private void addItemId(String itemId, Set<Integer> contextIds) {
        if (itemId == null || itemId.isBlank()) {
            return;
        }
        Integer internalId = IRBaseUtils.forwardLookupMap.get(IRObjectId.item(itemId.trim()));
        if (internalId != null) {
            contextIds.add(internalId);
        }
    }
}
