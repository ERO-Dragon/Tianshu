package com.rheinmetal.tianshu.intake.repair.enhance;

import com.rheinmetal.tianshu.intake.repair.input.IrPreparedInput;

public interface IrNamedObjectEnhancer {
    IrNamedObjectEnhancementResult enhance(IrPreparedInput input);

    default IrNamedObjectEnhancementResult enhance(IrPreparedInput input, IrContextHint contextHint) {
        return enhance(input);
    }

    static IrNamedObjectEnhancer noop() {
        return input -> IrNamedObjectEnhancementResult.empty(input == null ? "" : input.voiceText());
    }
}
