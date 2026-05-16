package com.rheinmetal.tianshu.function.ir.enhance;

import com.rheinmetal.tianshu.function.ir.input.IrPreparedInput;

public interface IrItemEnhancer {
    IrItemEnhancementResult enhance(IrPreparedInput input);

    static IrItemEnhancer noop() {
        return input -> IrItemEnhancementResult.empty(input == null ? "" : input.voiceText());
    }
}
