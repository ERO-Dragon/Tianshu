package com.rheinmetal.tianshu.function.ir.enhance;

import com.rheinmetal.tianshu.function.ir.input.IrPreparedInput;

public interface IrNamedObjectEnhancer {
    IrNamedObjectEnhancementResult enhance(IrPreparedInput input);

    default IrNamedObjectEnhancementResult enhance(IrPreparedInput input, IrContextHint contextHint) {
        return enhance(input);
    }

    static IrNamedObjectEnhancer noop() {
        return input -> IrNamedObjectEnhancementResult.empty(input == null ? "" : input.voiceText());
    }
}
