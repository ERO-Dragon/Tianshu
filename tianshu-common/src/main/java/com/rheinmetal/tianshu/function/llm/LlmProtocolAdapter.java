package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.function.llm.service.LLMService;
import com.rheinmetal.tianshu.function.llm.service.LlmRagStorageService;
import com.rheinmetal.tianshu.function.llm.service.RagCacheManager;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueLlmUsageAuthorizationRequestPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueLlmUsageAuthorizationResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMCacheManagePayload;
import com.rheinmetal.tianshu.protocol.payload.LLMCacheManageResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMRuntimeSnapshotPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.ModuleStatusPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;

public final class LlmProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = "module.llm";
    public static final String SOURCE_ID = "module.llm";

    private volatile LLMService llmService;
    private volatile LlmRagStorageService ragStorageService;
    private volatile Supplier<LLMRuntimeSnapshotPayload> unavailableRuntimeSnapshotSupplier;
    private final LlmTaskAdmissionController taskAdmissionController;
    private final LlmPromptPayloadMapper promptPayloadMapper;
    private final LlmPromptRequestHandler promptRequestHandler;

    public LlmProtocolAdapter(ModuleRuntimeAccess runtime, LLMService llmService) {
        this(runtime, llmService, new LlmTaskAdmissionController(0));
    }

    public LlmProtocolAdapter(
            ModuleRuntimeAccess runtime,
            LLMService llmService,
            LlmTaskAdmissionController taskAdmissionController
    ) {
        super(
                MODULE_ID,
                SOURCE_ID,
                runtime,
                AdapterDefaults.standard()
                        .withThreadPolicy(ThreadPolicy.IO_BLOCKING)
                        .withSupportsStreaming(true)
        );
        this.llmService = llmService;
        this.taskAdmissionController = taskAdmissionController == null
                ? new LlmTaskAdmissionController(0)
                : taskAdmissionController;
        this.promptPayloadMapper = new LlmPromptPayloadMapper();
        this.promptRequestHandler = new LlmPromptRequestHandler(
                this,
                this.taskAdmissionController,
                promptPayloadMapper
        );
    }

    public void setLlmService(LLMService llmService) {
        this.llmService = llmService;
        if (llmService == null) {
            taskAdmissionController.clearWaitingTasks("LLM_SERVICE_NOT_READY", "LLM service is not initialized");
        }
    }

    public void setRagStorageService(LlmRagStorageService ragStorageService) {
        this.ragStorageService = ragStorageService;
    }

    public void setUnavailableRuntimeSnapshotSupplier(Supplier<LLMRuntimeSnapshotPayload> supplier) {
        this.unavailableRuntimeSnapshotSupplier = supplier;
    }

    public void registerLLMRequestCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.LLM_REQUEST,
                PayloadType.LLM_PROMPT_REQUEST,
                LLMPromptRequestPayload.class,
                BrokerType.PARALLEL_LIMIT,
                EnumSet.of(PacketType.REQUEST, PacketType.COMMAND),
                Priority.NORMAL,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void registerLLMCacheManageCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.LLM_CACHE_MANAGE,
                PayloadType.LLM_CACHE_MANAGE,
                LLMCacheManagePayload.class,
                BrokerType.PARALLEL_LIMIT,
                EnumSet.of(PacketType.REQUEST, PacketType.COMMAND),
                Priority.NORMAL,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void registerLLMPrimitiveQueryCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.LLM_PRIMITIVE_QUERY,
                PayloadType.LLM_PRIMITIVE_QUERY,
                LLMPrimitiveQueryPayload.class,
                BrokerType.PARALLEL_LIMIT,
                EnumSet.of(PacketType.REQUEST, PacketType.COMMAND),
                Priority.NORMAL,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public TianshuEnvelope requestLLMPrimitiveQuery(LLMPrimitiveQueryPayload payload) {
        return requestCapability(
                ProtocolCapabilities.LLM_PRIMITIVE_QUERY,
                PayloadType.LLM_PRIMITIVE_QUERY,
                payload
        );
    }

    public TianshuEnvelope requestLLMPrimitiveQuery(TianshuEnvelope parent, LLMPrimitiveQueryPayload payload) {
        return requestCapability(
                parent,
                ProtocolCapabilities.LLM_PRIMITIVE_QUERY,
                PayloadType.LLM_PRIMITIVE_QUERY,
                payload
        );
    }

    public void registerLLMPrimitiveResultResponse(String requestEnvelopeId, EnvelopeHandler handler) {
        registerResponseHandler(
                requestEnvelopeId,
                PayloadType.LLM_PRIMITIVE_RESULT,
                LLMPrimitiveResultPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public TianshuEnvelope respondLLMPrimitiveResult(TianshuEnvelope parent, LLMPrimitiveResultPayload payload) {
        return respondTo(parent, PayloadType.LLM_PRIMITIVE_RESULT, payload);
    }

    public TianshuEnvelope requestLLM(LLMPromptRequestPayload payload) {
        return requestCapability(ProtocolCapabilities.LLM_REQUEST, PayloadType.LLM_PROMPT_REQUEST, payload);
    }

    public TianshuEnvelope requestLLM(TianshuEnvelope parent, LLMPromptRequestPayload payload) {
        return requestCapability(parent, ProtocolCapabilities.LLM_REQUEST, PayloadType.LLM_PROMPT_REQUEST, payload);
    }

    public TianshuEnvelope respondLLMPromptResult(TianshuEnvelope parent, LLMPromptResultPayload payload) {
        return respondTo(parent, PayloadType.LLM_PROMPT_RESULT, payload);
    }

    public TianshuEnvelope publishLLMPromptStreamChunk(
            TianshuEnvelope parent,
            LLMPromptStreamChunkPayload payload
    ) {
        return respondTo(parent, PayloadType.LLM_PROMPT_STREAM_CHUNK, payload);
    }

    public TianshuEnvelope publishLLMPromptStreamEnd(TianshuEnvelope parent, int index) {
        return publishLLMPromptStreamEnd(
                parent,
                index,
                "COMPLETED",
                LLMPromptResultPayload.TokenUsagePayload.empty(),
                null
        );
    }

    public TianshuEnvelope publishLLMPromptStreamEnd(
            TianshuEnvelope parent,
            int index,
            String finishType,
            LLMPromptResultPayload.TokenUsagePayload usage,
            String errorMessage
    ) {
        return publishLLMPromptStreamEnd(parent, index, finishType, usage, errorMessage, "");
    }

    public TianshuEnvelope publishLLMPromptStreamEnd(
            TianshuEnvelope parent,
            int index,
            String finishType,
            LLMPromptResultPayload.TokenUsagePayload usage,
            String errorMessage,
            String thinkingContent
    ) {
        return respondTo(
                parent,
                PayloadType.LLM_PROMPT_STREAM_CHUNK,
                LLMPromptStreamChunkPayload.end(
                        streamRequestId(parent),
                        index,
                        finishType,
                        usage,
                        errorMessage,
                        thinkingContent
                )
        );
    }

    public TianshuEnvelope publishInferenceStatus(LlmStatusPayload status) {
        if (status == null || runtime().topicSubscriberCount(ProtocolTopics.LLM_STATUS) == 0) {
            return null;
        }
        return publishTopic(ProtocolTopics.LLM_STATUS, PayloadType.LLM_STATUS, status);
    }

    public TianshuEnvelope publishModuleStatus(ModuleStatus status) {
        return status == null
                ? null
                : publishTopic(
                        ProtocolTopics.MODULE_STATUS,
                        PayloadType.MODULE_STATUS,
                        new ModuleStatusPayload(status)
                );
    }

    public TianshuEnvelope buildDialogueLlmUsageAuthorizationRequest(
            TianshuEnvelope parent,
            DialogueLlmUsageAuthorizationRequestPayload payload
    ) {
        return buildRequestCapability(
                parent,
                ProtocolCapabilities.DIALOGUE_LLM_USAGE_AUTHORIZE,
                PayloadType.DIALOGUE_LLM_USAGE_AUTHORIZATION_REQUEST,
                payload
        );
    }

    public TianshuEnvelope submitDialogueLlmUsageAuthorizationRequest(TianshuEnvelope envelope) {
        return submitPrepared(envelope);
    }

    public void registerDialogueLlmUsageAuthorizationResponse(String requestEnvelopeId, EnvelopeHandler handler) {
        registerResponseHandler(
                requestEnvelopeId,
                PayloadType.DIALOGUE_LLM_USAGE_AUTHORIZATION_RESULT,
                DialogueLlmUsageAuthorizationResultPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void handleLLMRequest(TianshuEnvelope envelope) {
        handleLLMRequest(envelope, null);
    }

    public void handleLLMRequest(TianshuEnvelope envelope, ProtocolContext context) {
        promptRequestHandler.handle(envelope, context);
    }

    LLMService currentLlmService() {
        return llmService;
    }

    boolean hasCapability(String capabilityId) {
        return runtime().capabilityProviderCount(capabilityId) > 0;
    }

    void unregisterAuthorizationResponse(String requestEnvelopeId) {
        unregisterResponseHandlers(requestEnvelopeId);
    }

    public void handleLLMCacheManage(TianshuEnvelope envelope) {
        handleLLMCacheManage(envelope, null);
    }

    public void handleLLMCacheManage(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope == null || !(envelope.payload() instanceof LLMCacheManagePayload payload)) {
            complete(context, envelope);
            return;
        }
        LLMService service = llmService;
        LlmRagStorageService storage = ragStorageService;
        if (service == null && storage == null) {
            respondTo(
                    envelope,
                    PayloadType.LLM_CACHE_MANAGE_RESULT,
                    LLMCacheManageResultPayload.failed(payload.uid(), "LLM service is not initialized")
            );
            fail(context, envelope, "LLM_SERVICE_NOT_READY", "LLM service is not initialized", null);
            return;
        }

        try {
            LLMCacheManageResultPayload result = switch (payload.action()) {
                case LLMCacheManagePayload.ACTION_UPSERT_ENTRY -> {
                    if (storage == null && service == null) {
                        yield LLMCacheManageResultPayload.failed(payload.uid(), "RAG storage is not initialized");
                    }
                    if (storage != null) {
                        LlmRagStorageService.RagWriteResult write = storage.upsert(payload.uid(), payload.entryId(), payload.content(), payload.vector());
                        yield write.success()
                                ? LLMCacheManageResultPayload.upserted(payload.uid(), payload.entryId())
                                : LLMCacheManageResultPayload.failed(payload.uid(), write.errorCode());
                    }
                    service.upsertRagEntry(payload.uid(), payload.entryId(), payload.content(), payload.vector());
                    yield LLMCacheManageResultPayload.upserted(payload.uid(), payload.entryId());
                }
                case LLMCacheManagePayload.ACTION_PATCH_ENTRY -> {
                    if (storage == null && service == null) {
                        yield LLMCacheManageResultPayload.failed(payload.uid(), "RAG storage is not initialized");
                    }
                    if (storage == null) {
                        service.patchRagEntry(payload.uid(), payload.entryId(), payload.content(), payload.vector(),
                                Boolean.TRUE.equals(payload.updateContent()), Boolean.TRUE.equals(payload.updateVector()));
                        yield LLMCacheManageResultPayload.patched(payload.uid(), payload.entryId(), service.hasRagEntry(payload.uid(), payload.entryId()));
                    }
                    LlmRagStorageService.RagWriteResult write = storage.patch(payload.uid(), payload.entryId(), payload.content(), payload.vector(),
                            Boolean.TRUE.equals(payload.updateContent()), Boolean.TRUE.equals(payload.updateVector()));
                    yield LLMCacheManageResultPayload.patched(
                            payload.uid(),
                            payload.entryId(),
                            write.success() && storage.hasEntry(payload.uid(), payload.entryId())
                    );
                }
                case LLMCacheManagePayload.ACTION_DELETE_ENTRY -> {
                    if (storage == null && service == null) {
                        yield LLMCacheManageResultPayload.failed(payload.uid(), "RAG storage is not initialized");
                    }
                    if (storage == null) {
                        service.deleteRagEntry(payload.uid(), payload.entryId());
                        yield LLMCacheManageResultPayload.deleted(payload.uid(), payload.entryId());
                    }
                    yield storage.delete(payload.uid(), payload.entryId())
                            ? LLMCacheManageResultPayload.deleted(payload.uid(), payload.entryId())
                            : LLMCacheManageResultPayload.failed(payload.uid(), "RAG_DELETE_FAILED");
                }
                case LLMCacheManagePayload.ACTION_CLEAR_UID -> {
                    if (storage == null && service == null) {
                        yield LLMCacheManageResultPayload.failed(payload.uid(), "RAG storage is not initialized");
                    }
                    if (storage == null) {
                        service.clearRagUid(payload.uid());
                        yield LLMCacheManageResultPayload.cleared(payload.uid());
                    }
                    yield storage.clear(payload.uid())
                            ? LLMCacheManageResultPayload.cleared(payload.uid())
                            : LLMCacheManageResultPayload.failed(payload.uid(), "RAG_CLEAR_FAILED");
                }
                case LLMCacheManagePayload.ACTION_REGISTER_LIBRARY -> {
                    if (storage == null && service == null) {
                        yield LLMCacheManageResultPayload.failed(payload.uid(), "RAG storage is not initialized");
                    }
                    if (storage == null) {
                        var library = service.registerRagLibrary(payload.uid(), payload.modid(), payload.visibility(), payload.tags());
                        yield LLMCacheManageResultPayload.registered(toLibraryPayload(library));
                    }
                    var library = storage.registerLibrary(
                            payload.uid(),
                            payload.modid(),
                            payload.visibility(),
                            payload.tags()
                    );
                    yield LLMCacheManageResultPayload.registered(toLibraryPayload(library));
                }
                case LLMCacheManagePayload.ACTION_UNREGISTER_LIBRARY -> {
                    if (storage == null && service == null) {
                        yield LLMCacheManageResultPayload.failed(payload.uid(), "RAG storage is not initialized");
                    }
                    if (storage == null) {
                        service.unregisterRagLibrary(payload.uid());
                        yield LLMCacheManageResultPayload.unregistered(payload.uid());
                    }
                    storage.unregisterLibrary(payload.uid());
                    yield LLMCacheManageResultPayload.unregistered(payload.uid());
                }
                case LLMCacheManagePayload.ACTION_QUERY_UID -> {
                    if (storage == null && service == null) {
                        yield LLMCacheManageResultPayload.failed(payload.uid(), "RAG storage is not initialized");
                    }
                    if (storage == null) {
                        yield LLMCacheManageResultPayload.queried(payload.uid(), service.hasRagUid(payload.uid()), toLibraryPayload(service.ragLibrary(payload.uid())));
                    }
                    boolean exists = storage.hasCache(payload.uid());
                    yield LLMCacheManageResultPayload.queried(
                            payload.uid(),
                            exists,
                            storage.library(payload.uid()) == null
                                    ? null
                                    : toLibraryPayload(storage.library(payload.uid()))
                    );
                }
                case LLMCacheManagePayload.ACTION_SEARCH_UID -> {
                    if (storage == null) {
                        yield LLMCacheManageResultPayload.failed(payload.uid(), "RAG storage is not initialized");
                    }
                    List<RagCacheManager.RagEntrySearchResult> entries = storage.searchEntries(payload.uid(), payload.queryText(), payload.topK(), payload.threshold());
                    yield LLMCacheManageResultPayload.searched(
                            payload.action(),
                            payload.uid(),
                            entries.isEmpty() ? List.of() : List.of(toHitGroup(payload.uid(), entries)),
                            storage.library(payload.uid()) == null
                                    ? List.of()
                                    : List.of(toLibraryPayload(storage.library(payload.uid())))
                    );
                }
                case LLMCacheManagePayload.ACTION_SEARCH_MODID -> {
                    if (service == null) {
                        if (storage == null) {
                            yield LLMCacheManageResultPayload.failed(payload.uid(), "RAG storage is not initialized");
                        }
                        List<LlmRagStorageService.LibrarySearchResult> results = storage.searchSharedByModid(
                                payload.modid(), payload.queryText(), payload.topK(), payload.threshold());
                        yield LLMCacheManageResultPayload.searched(
                                payload.action(), "", toStorageHitGroups(results), toStorageLibraryPayloads(results));
                    }
                    List<LLMService.RagLibrarySearchResult> results = service.searchSharedRagLibrariesByModid(
                            payload.modid(),
                            payload.queryText(),
                            payload.topK(),
                            payload.threshold()
                    );
                    yield LLMCacheManageResultPayload.searched(
                            payload.action(),
                            "",
                            toHitGroups(results),
                            toLibraryPayloads(results)
                    );
                }
                case LLMCacheManagePayload.ACTION_SEARCH_TAGS -> {
                    if (service == null) {
                        if (storage == null) {
                            yield LLMCacheManageResultPayload.failed(payload.uid(), "RAG storage is not initialized");
                        }
                        List<LlmRagStorageService.LibrarySearchResult> results = storage.searchSharedByTags(
                                payload.tags(), payload.queryText(), payload.topK(), payload.threshold());
                        yield LLMCacheManageResultPayload.searched(
                                payload.action(), "", toStorageHitGroups(results), toStorageLibraryPayloads(results));
                    }
                    List<LLMService.RagLibrarySearchResult> results = service.searchSharedRagLibrariesByTags(
                            payload.tags(),
                            payload.queryText(),
                            payload.topK(),
                            payload.threshold()
                    );
                    yield LLMCacheManageResultPayload.searched(
                            payload.action(),
                            "",
                            toHitGroups(results),
                            toLibraryPayloads(results)
                    );
                }
                case LLMCacheManagePayload.ACTION_SEARCH_INLINE_CONTENTS -> {
                    if (service == null) {
                        if (storage == null) {
                            yield LLMCacheManageResultPayload.failed(payload.uid(), "RAG storage is not initialized");
                        }
                        List<RagCacheManager.RagEntrySearchResult> entries = storage.searchInline(
                                payload.contents(), payload.queryText(), payload.topK(), payload.threshold());
                        List<LLMCacheManageResultPayload.HitGroupPayload> hits = entries.isEmpty()
                                ? List.of()
                                : List.of(toHitGroup(payload.uid(), entries));
                        yield LLMCacheManageResultPayload.searched(payload.action(), payload.uid(), hits, List.of());
                    }
                    List<RagCacheManager.RagEntrySearchResult> entries = service.searchInlineRagContents(
                            payload.contents(),
                            payload.queryText(),
                            payload.topK(),
                            payload.threshold()
                    );
                    List<LLMCacheManageResultPayload.HitGroupPayload> hits = entries.isEmpty()
                            ? List.of()
                            : List.of(toHitGroup(payload.uid(), entries));
                    yield LLMCacheManageResultPayload.searched(
                            payload.action(),
                            payload.uid(),
                            hits,
                            List.of()
                    );
                }
                default -> LLMCacheManageResultPayload.failed(
                        payload.uid(),
                        "Unknown action: " + payload.action()
                );
            };
            respondTo(envelope, PayloadType.LLM_CACHE_MANAGE_RESULT, result);
            complete(context, envelope);
        } catch (Exception exception) {
            respondTo(
                    envelope,
                    PayloadType.LLM_CACHE_MANAGE_RESULT,
                    LLMCacheManageResultPayload.failed(payload.uid(), exception.getMessage())
            );
            fail(context, envelope, "LLM_CACHE_MANAGE_FAILED", exception.getMessage(), exception);
        }
    }

    public void handleLLMPrimitiveQuery(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope == null || !(envelope.payload() instanceof LLMPrimitiveQueryPayload payload)) {
            complete(context, envelope);
            return;
        }
        try {
            LLMService service = llmService;
            LLMPrimitiveResultPayload result = switch (payload.queryType()) {
                case LLMPrimitiveQueryPayload.QUERY_TYPE_TOKEN_COUNT -> service != null
                        ? service.tokenCountResponse(
                                payload.requestId(),
                                promptPayloadMapper.toTokenCountRequest(payload)
                        )
                        : LLMPrimitiveResultPayload.failed(
                                payload.requestId(),
                                payload.queryType(),
                                "LLM_SERVICE_NOT_READY",
                                "LLM service is not initialized"
                        );
                case LLMPrimitiveQueryPayload.QUERY_TYPE_EMBED -> service != null
                        ? service.embedResponse(
                                payload.requestId(),
                                payload.texts(),
                                Boolean.TRUE.equals(payload.includeVector()),
                                Boolean.TRUE.equals(payload.includeEmbeddingDetails())
                        )
                        : LLMPrimitiveResultPayload.failed(
                                payload.requestId(),
                                payload.queryType(),
                                "LLM_SERVICE_NOT_READY",
                                "LLM service is not initialized"
                        );
                case LLMPrimitiveQueryPayload.QUERY_TYPE_STATUS -> service != null
                        ? service.runtimeSnapshotResponse(
                                payload.requestId(),
                                Boolean.TRUE.equals(payload.includeRuntimeDetails())
                        )
                        : LLMPrimitiveResultPayload.runtime(payload.requestId(), unavailableRuntimeSnapshot());
                default -> LLMPrimitiveResultPayload.failed(
                        payload.requestId(),
                        payload.queryType(),
                        "UNKNOWN_QUERY_TYPE",
                        "Unknown primitive query type"
                );
            };
            respondTo(envelope, PayloadType.LLM_PRIMITIVE_RESULT, result);
            complete(context, envelope);
        } catch (Exception exception) {
            respondTo(
                    envelope,
                    PayloadType.LLM_PRIMITIVE_RESULT,
                    LLMPrimitiveResultPayload.failed(
                            payload.requestId(),
                            payload.queryType(),
                            "LLM_PRIMITIVE_QUERY_FAILED",
                            exception.getMessage()
                    )
            );
            fail(context, envelope, "LLM_PRIMITIVE_QUERY_FAILED", exception.getMessage(), exception);
        }
    }

    private LLMRuntimeSnapshotPayload unavailableRuntimeSnapshot() {
        Supplier<LLMRuntimeSnapshotPayload> supplier = unavailableRuntimeSnapshotSupplier;
        if (supplier != null) {
            LLMRuntimeSnapshotPayload snapshot = supplier.get();
            if (snapshot != null) {
                return snapshot;
            }
        }
        return LLMRuntimeSnapshotPayload.unavailable();
    }

    private List<LLMCacheManageResultPayload.HitGroupPayload> toHitGroups(
            List<LLMService.RagLibrarySearchResult> results
    ) {
        return results == null || results.isEmpty()
                ? List.of()
                : results.stream()
                        .map(result -> LLMCacheManageResultPayload.HitGroupPayload.of(
                                result.uid(),
                                result.entries().stream()
                                        .map(hit -> LLMCacheManageResultPayload.HitEntryPayload.of(
                                                hit.entryId(),
                                                hit.content(),
                                                hit.score()
                                        ))
                                        .toList()
                        ))
                        .toList();
    }

    private List<LLMCacheManageResultPayload.HitGroupPayload> toStorageHitGroups(
            List<LlmRagStorageService.LibrarySearchResult> results
    ) {
        return results == null ? List.of() : results.stream()
                .map(result -> toHitGroup(result.uid(), result.entries()))
                .toList();
    }

    private List<LLMCacheManageResultPayload.LibraryPayload> toStorageLibraryPayloads(
            List<LlmRagStorageService.LibrarySearchResult> results
    ) {
        return results == null ? List.of() : results.stream()
                .map(result -> toLibraryPayload(result.library()))
                .toList();
    }

    private LLMCacheManageResultPayload.HitGroupPayload toHitGroup(
            String uid,
            List<RagCacheManager.RagEntrySearchResult> entries
    ) {
        return LLMCacheManageResultPayload.HitGroupPayload.of(
                uid,
                entries == null
                        ? List.of()
                        : entries.stream()
                                .map(hit -> LLMCacheManageResultPayload.HitEntryPayload.of(
                                        hit.entryId(),
                                        hit.content(),
                                        hit.score()
                                ))
                                .toList()
        );
    }

    private List<LLMCacheManageResultPayload.LibraryPayload> toLibraryPayloads(
            List<LLMService.RagLibrarySearchResult> results
    ) {
        return results == null || results.isEmpty()
                ? List.of()
                : results.stream()
                        .map(LLMService.RagLibrarySearchResult::library)
                        .filter(java.util.Objects::nonNull)
                        .map(this::toLibraryPayload)
                        .toList();
    }

    private LLMCacheManageResultPayload.LibraryPayload toLibraryPayload(
            com.rheinmetal.tianshu.function.llm.service.RagLibraryRegistry.RagLibraryMeta meta
    ) {
        return meta == null
                ? null
                : LLMCacheManageResultPayload.LibraryPayload.of(
                        meta.uid(),
                        meta.modid(),
                        meta.visibility(),
                        meta.tags()
                );
    }

    private static String streamRequestId(TianshuEnvelope parent) {
        if (parent != null && parent.payload() instanceof LLMPromptRequestPayload payload) {
            return payload.requestId();
        }
        return parent != null && parent.header() != null ? parent.header().traceId() : "";
    }

    private void complete(ProtocolContext context, TianshuEnvelope envelope) {
        if (context != null && envelope != null) {
            context.complete(envelope.envelopeId());
        }
    }

    private void fail(
            ProtocolContext context,
            TianshuEnvelope envelope,
            String code,
            String message,
            Throwable throwable
    ) {
        if (context != null && envelope != null) {
            context.fail(envelope.envelopeId(), code, message, throwable);
        }
    }
}
