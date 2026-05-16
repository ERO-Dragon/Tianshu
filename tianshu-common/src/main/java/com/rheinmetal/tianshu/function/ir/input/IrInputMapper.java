package com.rheinmetal.tianshu.function.ir.input;

import com.rheinmetal.tianshu.protocol.payload.AsrTextPayload;
import com.rheinmetal.tianshu.protocol.payload.IrParsePayload;

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

    public static IrInputText fromParse(IrParsePayload payload) {
        return new IrInputText(
                payload.text(),
                payload.rawText(),
                payload.turnId(),
                payload.sessionId(),
                payload.source(),
                System.currentTimeMillis()
        );
    }
}
