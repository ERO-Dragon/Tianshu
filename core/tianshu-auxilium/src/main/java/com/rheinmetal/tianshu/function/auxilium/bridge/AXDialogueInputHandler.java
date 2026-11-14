package com.rheinmetal.tianshu.function.auxilium.bridge;

import com.rheinmetal.tianshu.function.auxilium.input.AXDialogueInput;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;

/** Where a delivered spoken turn arrives, already translated into this module's own input type. */
@FunctionalInterface
public interface AXDialogueInputHandler {

    void handle(TianshuEnvelope envelope, AXDialogueInput input, ProtocolContext context) throws Exception;
}
