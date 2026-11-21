package com.rheinmetal.tianshu.dubhe.bridge;

import com.rheinmetal.tianshu.dubhe.input.DubheDialogueInput;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;

/** Where a delivered spoken turn arrives, already translated into this module's own input type. */
@FunctionalInterface
public interface DubheDialogueInputHandler {

    void handle(TianshuEnvelope envelope, DubheDialogueInput input, ProtocolContext context) throws Exception;
}
