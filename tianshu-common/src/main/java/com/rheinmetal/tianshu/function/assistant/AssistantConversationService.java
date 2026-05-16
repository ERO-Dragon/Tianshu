package com.rheinmetal.tianshu.function.assistant;

import com.rheinmetal.tianshu.function.assistant.context.AssistantGenerationOptionsFactory;
import com.rheinmetal.tianshu.function.assistant.context.AssistantContextCollector;
import com.rheinmetal.tianshu.function.assistant.context.AssistantContextOrchestrator;
import com.rheinmetal.tianshu.function.assistant.context.AssistantContextSnapshot;
import com.rheinmetal.tianshu.function.assistant.context.AssistantMessagePlan;
import com.rheinmetal.tianshu.function.assistant.input.AssistantInputNormalizer;
import com.rheinmetal.tianshu.function.assistant.input.AssistantNormalizedInput;
import com.rheinmetal.tianshu.function.assistant.output.AssistantOutputProcessor;
import com.rheinmetal.tianshu.function.assistant.runtime.AssistantRuntimeMaintenanceCoordinator;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantScope;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantScopeProvider;
import com.rheinmetal.tianshu.function.llm.inference.LlmGenerationOptions;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationRequest;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationResult;

public final class AssistantConversationService {
    private static final String EMPTY_INPUT_FALLBACK = "玩家刚才没有输入明确内容。请用自然、简短的方式提醒玩家可以继续告诉你想聊什么，或描述当前需要帮助的事情。";
    private final AssistantScopeProvider scopeProvider;
    private final AssistantContextCollector contextCollector;
    private final AssistantContextOrchestrator contextOrchestrator;
    private final AssistantRuntimeMaintenanceCoordinator maintenanceCoordinator;
    private final AssistantInputNormalizer inputNormalizer;
    private final AssistantOutputProcessor outputProcessor;
    private final AssistantGenerationOptionsFactory optionsFactory;

    public AssistantConversationService(
            AssistantScopeProvider scopeProvider,
            AssistantContextCollector contextCollector,
            AssistantContextOrchestrator contextOrchestrator,
            AssistantRuntimeMaintenanceCoordinator maintenanceCoordinator,
            AssistantInputNormalizer inputNormalizer,
            AssistantOutputProcessor outputProcessor,
            AssistantGenerationOptionsFactory optionsFactory
    ) {
        this.scopeProvider = scopeProvider;
        this.contextCollector = contextCollector;
        this.contextOrchestrator = contextOrchestrator == null ? new AssistantContextOrchestrator(null) : contextOrchestrator;
        this.maintenanceCoordinator = maintenanceCoordinator;
        this.inputNormalizer = inputNormalizer == null ? new AssistantInputNormalizer() : inputNormalizer;
        this.outputProcessor = outputProcessor == null ? new AssistantOutputProcessor(null) : outputProcessor;
        this.optionsFactory = optionsFactory == null ? new AssistantGenerationOptionsFactory(null) : optionsFactory;
    }

    public AssistantConversationService(
            AssistantScopeProvider scopeProvider,
            AssistantContextCollector contextCollector,
            AssistantContextOrchestrator contextOrchestrator,
            AssistantRuntimeMaintenanceCoordinator maintenanceCoordinator,
            AssistantInputNormalizer inputNormalizer,
            AssistantOutputProcessor outputProcessor
    ) {
        this(scopeProvider, contextCollector, contextOrchestrator, maintenanceCoordinator, inputNormalizer, outputProcessor, null);
    }

    public AssistantInvocationPlan prepareInvocation(AssistantRequest request) {
        AssistantNormalizedInput normalizedInput = inputNormalizer.normalize(request);
        AssistantRequest effectiveRequest = effectiveRequest(normalizedInput);
        AssistantScope scope = scopeProvider == null ? AssistantScope.unknown() : scopeProvider.currentScope();
        if (maintenanceCoordinator != null) {
            maintenanceCoordinator.beforeQuestion(scope, effectiveRequest);
        }
        AssistantContextSnapshot snapshot = contextCollector == null
                ? new AssistantContextSnapshot(scope, null, java.util.List.of(), effectiveRequest.providedContext())
                : contextCollector.collect(scope, effectiveRequest);
        AssistantMessagePlan messagePlan = contextOrchestrator.buildMessages(effectiveRequest, snapshot);
        outputProcessor.recordUserInput(scope, effectiveRequest);
        LlmGenerationOptions options = optionsFactory.create(snapshot);
        LlmInvocationRequest invocationRequest = new LlmInvocationRequest(effectiveRequest.requestKey(), messagePlan.messages(), options, messagePlan.ragContext());
        return new AssistantInvocationPlan(effectiveRequest, scope, invocationRequest);
    }

    public void completeInvocation(AssistantInvocationPlan plan, LlmInvocationResult result) {
        if (plan == null || result == null) {
            return;
        }
        outputProcessor.recordAssistantOutput(plan.scope(), plan.assistantRequest(), result.text());
        outputProcessor.recordRagHits(plan.scope(), result.ragHits());
    }

    public void completeInvocation(AssistantInvocationPlan plan, String assistantText) {
        if (plan == null) {
            return;
        }
        outputProcessor.recordAssistantOutput(plan.scope(), plan.assistantRequest(), assistantText);
    }

    private AssistantRequest effectiveRequest(AssistantNormalizedInput normalizedInput) {
        AssistantRequest request = AssistantRequest.fromNormalizedInput(normalizedInput);
        if (normalizedInput == null || !normalizedInput.empty()) {
            return request;
        }
        return new AssistantRequest(request.requestKey(), EMPTY_INPUT_FALLBACK, request.providedContext());
    }
}
