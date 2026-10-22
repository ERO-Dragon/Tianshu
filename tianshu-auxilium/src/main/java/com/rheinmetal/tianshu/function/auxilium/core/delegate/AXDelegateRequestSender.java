package com.rheinmetal.tianshu.function.auxilium.core.delegate;

import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmRequestHandler;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;

/**
 * 把一次 Complex 请求交出去（设计 §5.1 步骤 ⑥）。
 *
 * <p>生产实现是 {@code AXLlmClient::submitDetached}：委派请求没有 parent 信封，也不进会话释放表。这里是
 * 一个函数式缝——服务因此只依赖"发出一个请求"这件事，而不是整个 LLM 客户端，测试可以直接捕获请求并回灌
 * 结果。</p>
 */
@FunctionalInterface
public interface AXDelegateRequestSender {

    void send(LLMPromptRequestPayload payload, AXLlmRequestHandler handler);
}
