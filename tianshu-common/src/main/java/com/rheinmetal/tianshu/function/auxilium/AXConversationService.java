package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.auxilium.context.AXGenerationOptionsFactory;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextCollector;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextOrchestrator;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.context.AXMessagePlan;
import com.rheinmetal.tianshu.function.auxilium.input.AXInputNormalizer;
import com.rheinmetal.tianshu.function.auxilium.input.AXNormalizedInput;
import com.rheinmetal.tianshu.function.auxilium.output.AXOutputProcessor;
import com.rheinmetal.tianshu.function.auxilium.runtime.AXRuntimeMaintenanceCoordinator;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeProvider;
import com.rheinmetal.tianshu.function.llm.inference.LlmGenerationOptions;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationRequest;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationResult;

public final class AXConversationService {
    private static final String EMPTY_INPUT_FALLBACK = "玩家刚才没有输入明确内容。请用自然、简短的方式提醒玩家可以继续告诉你想聊什么，或描述当前需要帮助的事情。";
    private final AXScopeProvider scopeProvider;
    private final AXContextCollector contextCollector;
    private final AXContextOrchestrator contextOrchestrator;
    private final AXRuntimeMaintenanceCoordinator maintenanceCoordinator;
    private final AXInputNormalizer inputNormalizer;
    private final AXOutputProcessor outputProcessor;
    private final AXGenerationOptionsFactory optionsFactory;

    public AXConversationService(
            AXScopeProvider scopeProvider,
            AXContextCollector contextCollector,
            AXContextOrchestrator contextOrchestrator,
            AXRuntimeMaintenanceCoordinator maintenanceCoordinator,
            AXInputNormalizer inputNormalizer,
            AXOutputProcessor outputProcessor,
            AXGenerationOptionsFactory optionsFactory
    ) {
        this.scopeProvider = scopeProvider;
        this.contextCollector = contextCollector;
        this.contextOrchestrator = contextOrchestrator == null ? new AXContextOrchestrator(null) : contextOrchestrator;
        this.maintenanceCoordinator = maintenanceCoordinator;
        this.inputNormalizer = inputNormalizer == null ? new AXInputNormalizer() : inputNormalizer;
        this.outputProcessor = outputProcessor == null ? new AXOutputProcessor(null) : outputProcessor;
        this.optionsFactory = optionsFactory == null ? new AXGenerationOptionsFactory(null) : optionsFactory;
    }

    public AXConversationService(
            AXScopeProvider scopeProvider,
            AXContextCollector contextCollector,
            AXContextOrchestrator contextOrchestrator,
            AXRuntimeMaintenanceCoordinator maintenanceCoordinator,
            AXInputNormalizer inputNormalizer,
            AXOutputProcessor outputProcessor
    ) {
        this(scopeProvider, contextCollector, contextOrchestrator, maintenanceCoordinator, inputNormalizer, outputProcessor, null);
    }

    public AXInvocationPlan prepareInvocation(AXRequest request) {
        AXNormalizedInput normalizedInput = inputNormalizer.normalize(request);
        AXRequest effectiveRequest = effectiveRequest(normalizedInput);
        AXScope scope = scopeProvider == null ? AXScope.unknown() : scopeProvider.currentScope();
        if (maintenanceCoordinator != null) {
            maintenanceCoordinator.beforeQuestion(scope, effectiveRequest);
        }
        AXContextSnapshot snapshot = contextCollector == null
                ? new AXContextSnapshot(scope, null, java.util.List.of(), effectiveRequest.providedContext())
                : contextCollector.collect(scope, effectiveRequest);
        AXMessagePlan messagePlan = contextOrchestrator.buildMessages(effectiveRequest, snapshot);
        outputProcessor.recordUserInput(scope, effectiveRequest);
        LlmGenerationOptions options = optionsFactory.create(snapshot);
        LlmInvocationRequest invocationRequest = new LlmInvocationRequest(effectiveRequest.requestKey(), messagePlan.messages(), options, messagePlan.ragContext());
        return new AXInvocationPlan(effectiveRequest, scope, invocationRequest);
    }

    public void completeInvocation(AXInvocationPlan plan, LlmInvocationResult result) {
        if (plan == null || result == null) {
            return;
        }
        outputProcessor.recordAXOutput(plan.scope(), plan.AXRequest(), result.text());
        outputProcessor.recordRagHits(plan.scope(), result.ragHits());
    }

    public void completeInvocation(AXInvocationPlan plan, String AXText) {
        if (plan == null) {
            return;
        }
        outputProcessor.recordAXOutput(plan.scope(), plan.AXRequest(), AXText);
    }

    private AXRequest effectiveRequest(AXNormalizedInput normalizedInput) {
        AXRequest request = AXRequest.fromNormalizedInput(normalizedInput);
        if (normalizedInput == null || !normalizedInput.empty()) {
            return request;
        }
        return new AXRequest(request.requestKey(), EMPTY_INPUT_FALLBACK, request.providedContext());
    }
}
