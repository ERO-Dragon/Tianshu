package com.rheinmetal.tianshu.function.ir.input;

import com.rheinmetal.tianshu.protocol.payload.AsrTextPayload;

public final class IrInputMapper {
    private IrInputMapper() {
    }

    public static IrInputText fromAsr(AsrTextPayload payload) {
        return new IrInputText(
                payload.text(),
                payload.rawText(),
                payload.turnId(),
                payload.sessionId(),
                "asr:" + payload.inputMode(),
                payload.createdAt()
        );
    }
}
