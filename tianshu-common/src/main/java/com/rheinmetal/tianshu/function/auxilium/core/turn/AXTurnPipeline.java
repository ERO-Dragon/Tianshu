package com.rheinmetal.tianshu.function.auxilium.core.turn;

import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

/**
 * 一期对话编排入口。一期实现为 {@link AXTurnOrchestrator}（简单对话链路）；
 * 二期 agent 化时可在 core/turn 下新增 {@code AXAgentTurnOrchestrator} 等实现，
 * 由 {@link AXDialogueGateway} 在装配时切换，无需改动 gateway 与 IA 的契约。
 *
 * 设计约束：实现方负责在 IA 授权会话内完成输入规范化、上下文组织、LLM 调用、
 * 输出收口和会话释放；不得绕过协议中心或自行仲裁对话所有权。
 */
public interface AXTurnPipeline {
    void startTurn(TianshuEnvelope deliveryEnvelope, DialogueDeliveryPayload delivery);
}
