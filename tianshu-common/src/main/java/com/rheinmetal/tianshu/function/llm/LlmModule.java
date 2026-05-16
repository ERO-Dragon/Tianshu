package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.INativeLibBridge;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.core.runtime.RuntimeCapability;
import com.rheinmetal.tianshu.core.scope.DefaultWorldIdentityProvider;
import com.rheinmetal.tianshu.core.scope.DefaultWorldScopeProvider;
import com.rheinmetal.tianshu.core.scope.WorldScopeProvider;
import com.rheinmetal.tianshu.function.llm.engine.LlmEngine;
import com.rheinmetal.tianshu.function.llm.gateway.DefaultLlmTaskGatewayService;
import com.rheinmetal.tianshu.function.llm.gateway.IaBackedLlmUsageAuthorizer;
import com.rheinmetal.tianshu.function.llm.gateway.LlmGatewayAdmissionResult;
import com.rheinmetal.tianshu.function.llm.gateway.LlmGatewayPolicy;
import com.rheinmetal.tianshu.function.llm.gateway.LlmGatewayRequest;
import com.rheinmetal.tianshu.function.llm.gateway.LlmGatewayUsageKind;
import com.rheinmetal.tianshu.function.llm.gateway.LlmUsageAuthorization;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationMessage;
import com.rheinmetal.tianshu.function.llm.inference.LlmMessageRole;
import com.rheinmetal.tianshu.function.llm.inference.LlmRagRoutingContext;
import com.rheinmetal.tianshu.function.llm.rag.LlmRagPathResolution;
import com.rheinmetal.tianshu.function.llm.rag.LlmRagPathResolver;
import com.rheinmetal.tianshu.function.llm.server.LlmServerProcessManager;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.LlmRagPathRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmRagPathResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskMessagePayload;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskResultPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.List;

public final class LlmModule implements TianshuManagedModule {
    private static final List<RuntimeCapability> PROVIDED_CAPABILITIES = List.of(
            LlmRuntimeCapabilities.INFERENCE,
            LlmRuntimeCapabilities.TASK
    );

    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final INativeLibBridge nativeLibBridge;
    private final ProtocolRuntime runtime;
    private final WorldScopeProvider scopeProvider;
    private final LlmEngineProvider engineProvider;
    private final LlmEngine llmEngine;
    private final LlmInvocationService invocationService;
    private final LlmProtocolAdapter adapter;
    private final LlmRagPathResolver ragPathResolver;
    private final IaBackedLlmUsageAuthorizer usageAuthorizer;
    private final DefaultLlmTaskGatewayService taskGatewayService;
    private ModuleRuntimeContext runtimeContext;
    private LlmServerProcessManager processManager;

    public LlmModule(IGameEnvironment env, ITianshuConfig config, INativeLibBridge nativeLibBridge, ProtocolRuntime runtime) {
        this.env = env;
        this.config = config;
        this.nativeLibBridge = nativeLibBridge;
        this.runtime = runtime;
        this.scopeProvider = new DefaultWorldScopeProvider(new DefaultWorldIdentityProvider(env));
        this.engineProvider = new LlmEngineProvider(env, config);
        LlmEngine llmEngine = engineProvider.getLlmEngine();
        this.llmEngine = llmEngine;
        this.adapter = new LlmProtocolAdapter(runtime);
        this.invocationService = new LlmInvocationService(llmEngine, adapter);
        this.ragPathResolver = new LlmRagPathResolver(config, scopeProvider);
        this.usageAuthorizer = new IaBackedLlmUsageAuthorizer(adapter);
        this.taskGatewayService = new DefaultLlmTaskGatewayService(invocationService, adapter, LlmGatewayPolicy.DEFAULT, usageAuthorizer);
    }

    @Override
    public String moduleId() {
        return "module.llm";
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        adapter.registerTaskRequestCapability(this::handleTaskRequest);
        adapter.registerRagPathResolveCapability(this::handleRagPathResolve);
        adapter.registerLlmUsageAuthorizationResultRoute(this::handleUsageAuthorizationResult);
        context.services().register(LlmInvocationService.class, invocationService);
    }

    private void handleUsageAuthorizationResult(TianshuEnvelope envelope, ProtocolContext context) {
        try {
            usageAuthorizer.handleAuthorizationResult(envelope, taskGatewayService::handleAuthorizationCompletion);
        } finally {
            context.complete(envelope.envelopeId());
        }
    }

    private void handleRagPathResolve(TianshuEnvelope envelope, ProtocolContext context) {
        try {
            if (!(envelope.payload() instanceof LlmRagPathRequestPayload payload)) {
                adapter.respondRagPathResult(envelope, LlmRagPathResultPayload.failed("llm.rag.path", "INVALID_PAYLOAD", "Invalid LLM RAG path request payload"));
                return;
            }
            LlmRagPathResolution resolution = ragPathResolver.resolveCurrent(payload.moduleId(), payload.agentId());
            adapter.respondRagPathResult(envelope, new LlmRagPathResultPayload(
                    payload.requestId(),
                    "OK",
                    resolution.worldId(),
                    resolution.moduleId(),
                    resolution.agentId(),
                    resolution.profile(),
                    resolution.ragRoot().toString(),
                    resolution.worldRoot().toString(),
                    resolution.profilesFile().toString(),
                    resolution.moduleRoot().toString(),
                    resolution.staticRagRoot().toString(),
                    resolution.agentRoot().toString(),
                    resolution.memoryRagRoot().toString(),
                    resolution.memoriesFile().toString(),
                    "",
                    ""
            ));
        } catch (RuntimeException ex) {
            adapter.respondRagPathResult(envelope, LlmRagPathResultPayload.failed("llm.rag.path", "RAG_PATH_RESOLVE_FAILED", ex.getMessage()));
        } finally {
            context.complete(envelope.envelopeId());
        }
    }

    @Override
    public void prepare(ModuleRuntimeContext context) {
        runtimeContext = context;
        markCapabilitiesInstalled(context);
        llmEngine.initialize("http://127.0.0.1:" + config.getLlmPort());
        processManager = new LlmServerProcessManager(env, config, nativeLibBridge, runtime.executors(), () -> {
            markCapabilitiesReady();
            env.executeOnMainThread(() -> env.displayMessageToPlayer("§b[天极] §f中枢核心已就绪"));
        }, () -> markCapabilitiesFailed("LLM server is not ready"));
    }

    @Override
    public void start(ModuleRuntimeContext context) {
        if (processManager != null) {
            processManager.startLlmServer();
        }
    }

    @Override
    public void stop() {
        taskGatewayService.shutdown();
        invocationService.cancelActiveGeneration();
        if (processManager != null) {
            processManager.stopLlmServer();
        }
    }

    @Override
    public void destroy() {
        stop();
        engineProvider.stop();
        if (runtimeContext != null) {
            PROVIDED_CAPABILITIES.forEach(runtimeContext.runtimeState().capabilities()::remove);
        }
        runtimeContext = null;
        processManager = null;
    }

    private void handleTaskRequest(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof LlmTaskRequestPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "LLM task payload is invalid", null);
            return;
        }
        LlmGatewayRequest request = toGatewayRequest(envelope, payload);
        LlmGatewayAdmissionResult admission = taskGatewayService.submit(request, envelope);
        if (!admission.accepted()) {
            context.fail(envelope.envelopeId(), admission.error().code(), admission.error().message(), null);
            adapter.respondTaskResult(envelope, new LlmTaskResultPayload(
                    request.taskId(),
                    request.purpose(),
                    "REJECTED",
                    "",
                    admission.error().code(),
                    admission.error().message()
            ));
            return;
        }
        context.complete(envelope.envelopeId());
    }

    private LlmGatewayRequest toGatewayRequest(TianshuEnvelope envelope, LlmTaskRequestPayload payload) {
        long now = System.currentTimeMillis();
        String taskId = payload.taskId() == null || payload.taskId().isBlank() ? envelope.envelopeId() : payload.taskId();
        String worldId = scopeProvider.currentScope().worldId();
        return new LlmGatewayRequest(
                taskId,
                payload.purpose(),
                LlmGatewayUsageKind.fromName(payload.usageKind().name()),
                envelope.header().sourceId(),
                envelope.traceId(),
                payload.messages().stream().map(this::toInvocationMessage).toList(),
                payload.dynamicFacts(),
                payload.taskPriority(),
                payload.taskPreemptible(),
                payload.stream(),
                payload.thinking(),
                payload.useRag(),
                payload.maxTokens(),
                payload.temperature(),
                new LlmRagRoutingContext(worldId, payload.moduleId(), payload.agentId(), payload.staticScope(), payload.staticMods()),
                new LlmUsageAuthorization(
                        payload.authorization().sessionId(),
                        payload.authorization().turnId()
                ),
                payload.expireAtMillis() > 0L ? payload.expireAtMillis() : envelope.header().expireAt(),
                now
        );
    }

    private LlmInvocationMessage toInvocationMessage(LlmTaskMessagePayload message) {
        LlmMessageRole role = switch (message.role()) {
            case "system" -> LlmMessageRole.SYSTEM;
            case "assistant" -> LlmMessageRole.ASSISTANT;
            default -> LlmMessageRole.USER;
        };
        return new LlmInvocationMessage(role, message.content());
    }

    private void markCapabilitiesInstalled(ModuleRuntimeContext context) {
        PROVIDED_CAPABILITIES.forEach(capability -> context.runtimeState().capabilities().install(capability, moduleId()));
    }

    private void markCapabilitiesReady() {
        ModuleRuntimeContext context = runtimeContext;
        if (context == null) {
            return;
        }
        PROVIDED_CAPABILITIES.forEach(capability -> context.runtimeState().capabilities().markReady(capability, moduleId()));
    }

    private void markCapabilitiesFailed(String reason) {
        ModuleRuntimeContext context = runtimeContext;
        if (context == null) {
            return;
        }
        PROVIDED_CAPABILITIES.forEach(capability -> context.runtimeState().capabilities().markFailed(capability, moduleId(), reason));
    }
}
