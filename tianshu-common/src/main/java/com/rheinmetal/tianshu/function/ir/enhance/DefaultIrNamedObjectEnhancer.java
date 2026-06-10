package com.rheinmetal.tianshu.function.ir.enhance;

import com.rheinmetal.tianshu.function.ir.core.IRCommandService;
import com.rheinmetal.tianshu.function.ir.core.IRParseResult;
import com.rheinmetal.tianshu.function.ir.core.ParseUnit;
import com.rheinmetal.tianshu.function.ir.input.IrPreparedInput;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class DefaultIrNamedObjectEnhancer implements IrNamedObjectEnhancer {
    private final IRCommandService commandService;

    public DefaultIrNamedObjectEnhancer(IRCommandService commandService) {
        this.commandService = commandService;
    }

    @Override
    public IrNamedObjectEnhancementResult enhance(IrPreparedInput input) {
        if (input == null || input.filteredText().isBlank() || commandService == null || !commandService.isReady()) {
            return IrNamedObjectEnhancementResult.empty(input == null ? "" : input.voiceText());
        }
        IRParseResult parseResult = commandService.parse(input.filteredText(), null, true);
        if (parseResult == null) {
            return IrNamedObjectEnhancementResult.empty(input.filteredText());
        }
        Set<String> matchedIdSet = new LinkedHashSet<>(parseResult.getMatchedItemRealIds());
        Set<String> matchedEntityTypeSet = new LinkedHashSet<>(parseResult.getMatchedEntityTypeIds());
        for (ParseUnit unit : parseResult.getUnits()) {
            if (unit == null || unit.targetRealItemId == null || unit.targetRealItemId.isBlank()) {
                continue;
            }
            matchedIdSet.add(unit.targetRealItemId.trim());
        }

        String repairedText = parseResult.getHealedRawText();
        if (repairedText == null || repairedText.isBlank()) {
            repairedText = input.filteredText();
        }
        if (matchedIdSet.isEmpty() && matchedEntityTypeSet.isEmpty() && repairedText.equals(input.filteredText())) {
            return IrNamedObjectEnhancementResult.empty(input.filteredText());
        }

        List<String> matchedIds = new ArrayList<>(matchedIdSet);
        List<String> matchedNames = new ArrayList<>();
        for (String itemId : matchedIds) {
            String displayName = commandService.resolveDisplayName(itemId);
            if (!displayName.isBlank()) {
                matchedNames.add(displayName);
            }
        }
        List<String> matchedEntityTypeIds = new ArrayList<>(matchedEntityTypeSet);
        List<String> matchedEntityNames = new ArrayList<>();
        for (String entityTypeId : matchedEntityTypeIds) {
            String displayName = commandService.resolveEntityDisplayName(entityTypeId);
            if (!displayName.isBlank()) {
                matchedEntityNames.add(displayName);
            }
        }
        return new IrNamedObjectEnhancementResult(repairedText, matchedNames, matchedIds, matchedEntityNames, matchedEntityTypeIds, !matchedIds.isEmpty() || !matchedEntityTypeIds.isEmpty());
    }
}
