package com.rheinmetal.tianshu.function.ir.enhance;

import com.rheinmetal.tianshu.function.ir.core.IRCommandService;
import com.rheinmetal.tianshu.function.ir.core.IRParseResult;
import com.rheinmetal.tianshu.function.ir.core.ParseUnit;
import com.rheinmetal.tianshu.function.ir.input.IrPreparedInput;

import java.util.ArrayList;
import java.util.List;

public final class DefaultIrItemEnhancer implements IrItemEnhancer {
    private final IRCommandService commandService;

    public DefaultIrItemEnhancer(IRCommandService commandService) {
        this.commandService = commandService;
    }

    @Override
    public IrItemEnhancementResult enhance(IrPreparedInput input) {
        if (input == null || input.filteredText().isBlank() || commandService == null || !commandService.isReady()) {
            return IrItemEnhancementResult.empty(input == null ? "" : input.voiceText());
        }
        IRParseResult parseResult = commandService.parse(input.filteredText(), null, true);
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
            matchedNames.add(unit.targetRealItemId.trim());
        }
        String repairedText = parseResult.getHealedRawText();
        if (repairedText == null || repairedText.isBlank()) {
            repairedText = input.filteredText();
        }
        return new IrItemEnhancementResult(repairedText, matchedNames, matchedIds, !matchedIds.isEmpty());
    }
}
