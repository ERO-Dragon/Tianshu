package com.rheinmetal.tianshu.client.ir;

import com.rheinmetal.tianshu.function.ir.enhance.IrItemEnhancementResult;
import com.rheinmetal.tianshu.function.ir.enhance.IrItemEnhancer;
import com.rheinmetal.tianshu.function.ir.input.IrPreparedInput;
import com.rheinmetal.tianshu.function.ir.core.IRParseResult;
import com.rheinmetal.tianshu.function.ir.core.ParseUnit;

import java.util.ArrayList;
import java.util.List;

final class ClientIrItemEnhancer implements IrItemEnhancer {
    @Override
    public IrItemEnhancementResult enhance(IrPreparedInput input) {
        if (input == null || input.filteredText().isBlank()) {
            return IrItemEnhancementResult.empty(input == null ? "" : input.voiceText());
        }
        IRParseResult parseResult = ClientItemCommandManager.parsePlayerCommand(input.filteredText(), true);
        if (parseResult == null || !parseResult.hasUnits()) {
            return IrItemEnhancementResult.empty(input.filteredText());
        }
        List<String> matchedIds = new ArrayList<>();
        List<String> matchedNames = new ArrayList<>();
        for (ParseUnit unit : parseResult.getUnits()) {
            if (unit == null || unit.targetRealItemId == null || unit.targetRealItemId.isBlank()) {
                continue;
            }
            matchedIds.add(unit.targetRealItemId.trim());
            matchedNames.add(resolveDisplayName(unit.targetRealItemId));
        }
        String repairedText = parseResult.getHealedRawText();
        if (repairedText == null || repairedText.isBlank()) {
            repairedText = input.filteredText();
        }
        return new IrItemEnhancementResult(repairedText, matchedNames, matchedIds, !matchedIds.isEmpty());
    }

    private String resolveDisplayName(String realItemId) {
        if (realItemId == null || realItemId.isBlank()) {
            return "";
        }
        int index = realItemId.lastIndexOf(':');
        String tail = index >= 0 ? realItemId.substring(index + 1) : realItemId;
        return tail.replace('_', ' ').trim();
    }
}
