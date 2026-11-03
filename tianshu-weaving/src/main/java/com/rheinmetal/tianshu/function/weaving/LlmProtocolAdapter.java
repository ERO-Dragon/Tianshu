package com.rheinmetal.tianshu.function.weaving;

import com.rheinmetal.tianshu.function.weaving.bridge.LlmPresenceActivityBridge;
import com.rheinmetal.tianshu.function.weaving.contract.LLMCacheManagePayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMCacheManageResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMRuntimeSnapshotPayload;
import com.rheinmetal.tianshu.function.weaving.protocol.LlmCapabilities;
import com.rheinmetal.tianshu.function.weaving.protocol.LlmModuleId;
import com.rheinmetal.tianshu.function.weaving.service.LLMService;
import com.rheinmetal.tianshu.function.weaving.service.LlmPromptFactory;
import com.rheinmetal.tianshu.function.weaving.service.LlmRagStorageService;
import com.rheinmetal.tianshu.function.weaving.service.RagCacheManager;
import com.rheinmetal.tianshu.function.weaving.service.RagLibraryRegistry;
import com.rheinmetal.tianshu.function.weaving.usage.LlmUsageLedger;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.ModuleStatusPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Protocol face of the cloud lane: three capabilities, their responses, and the HUD notices that
 * belong to the lane itself.
 *
 * <p>Request execution lives in {@link LlmPromptRequestHandler}; this class only registers
 * capabilities, publishes responses and keeps the presence activities in sync.</p>
 */
public final class LlmProtocolAdapter extends AbstractProtocolAdapter {
    /** Backstop for a process that dies mid-load; a real start ends the notice explicitly. */
    private static final long MODEL_LOADING_TTL_MILLIS = 120_000L;
    public static final String MODULE_ID = LlmModuleId.MODULE;
    public static final String SOURCE_ID = LlmProtocolAdapter.MODULE_ID;

    private volatile LLMService llmService;
    private volatile LlmRagStorageService ragStorageService;
    private volatile Supplier<LLMRuntimeSnapshotPayload> unavailableRuntimeSnapshotSupplier;
    /** Runtime notices currently published, so recovery ends exactly the ones that are up. */
    private final Set<LlmPresenceActivityBridge.Kind> activeRuntimeActivities = ConcurrentHashMap.newKeySet();
    /** The optional HUD integration; a no-op when presence is not installed. */
    private final LlmPresenceActivityBridge presenceActivity;
    /** Whether the startup notice is up; a clean start never sends an empty retraction. */
    private final AtomicBoolean loadingPublished = new AtomicBoolean();
    private final LlmPromptFactory promptFactory;
    private final LlmPromptRequestHandler promptRequestHandler;

    public LlmProtocolAdapter(ModuleRuntimeAccess runtime, LLMService llmService) {
        this(runtime, llmService, new LlmPromptFactory(() -> ""), new LlmUsageLedger());
    }

    /**
     * @param promptFactory turns protocol messages into a sendable prompt; the module passes one
     *                      that knows the configured model so catalog sampling defaults apply
     * @param usageLedger   ledger this adapter's requests are accounted into; the owning module
     *                      passes the same instance it exposes to readers
     */
    public LlmProtocolAdapter(
            ModuleRuntimeAccess runtime,
            LLMService llmService,
            LlmPromptFactory promptFactory,
            LlmUsageLedger usageLedger
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
        this.presenceActivity = LlmPresenceActivityBridge.of(runtime, SOURCE_ID);
        this.promptFactory = promptFactory == null ? new LlmPromptFactory(() -> "") : promptFactory;
        this.promptRequestHandler = new LlmPromptRequestHandler(
                this,
                this.promptFactory,
                usageLedger == null ? new LlmUsageLedger() : usageLedger
        );
    }

    public void setLlmService(LLMService llmService) {
        this.llmService = llmService;
    }

    public void setRagStorageService(LlmRagStorageService ragStorageService) {
        this.ragStorageService = ragStorageService;
    }

    public void setUnavailableRuntimeSnapshotSupplier(Supplier<LLMRuntimeSnapshotPayload> supplier) {
        this.unavailableRuntimeSnapshotSupplier = supplier;
    }

    // --- capability registration -----------------------------------------------------------------

    public void registerLLMRequestCapability(EnvelopeHandler handler) {
        registerCapability(
                LlmCapabilities.LLM_REQUEST,
                PayloadType.of("LLM_PROMPT_REQUEST"),
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
                LlmCapabilities.LLM_CACHE_MANAGE,
                PayloadType.of("LLM_CACHE_MANAGE"),
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
                LlmCapabilities.LLM_PRIMITIVE_QUERY,
                PayloadType.of("LLM_PRIMITIVE_QUERY"),
                LLMPrimitiveQueryPayload.class,
                BrokerType.PARALLEL_LIMIT,
                EnumSet.of(PacketType.REQUEST, PacketType.COMMAND),
                Priority.NORMAL,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    // --- responses and HUD notices ---------------------------------------------------------------

    public TianshuEnvelope respondLLMPromptResult(TianshuEnvelope parent, LLMPromptResultPayload payload) {
        return respondTo(parent, PayloadType.of("LLM_PROMPT_RESULT"), payload);
    }

    public TianshuEnvelope publishLLMPromptStreamChunk(
            TianshuEnvelope parent,
            LLMPromptStreamChunkPayload payload
    ) {
        return respondTo(parent, PayloadType.of("LLM_PROMPT_STREAM_CHUNK"), payload);
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
                PayloadType.of("LLM_PROMPT_STREAM_CHUNK"),
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

    /**
     * Opens or closes the "starting up" notice of the cloud lane.
     *
     * <p>The lane has no model file to mmap, but from the player's side it is loading all the same:
     * the request capability is not usable until the client is built and the embedding engine is
     * being brought up. Presence shows it as {@code LOADING}, and the platform keeps module keys
     * unavailable while it is up.</p>
     */
    public TianshuEnvelope publishLoadingActivity(boolean loading) {
        String activityId = "llm.model.load";
        if (loading) {
            loadingPublished.set(true);
            return presenceActivity.publish(
                    activityId, LlmPresenceActivityBridge.Kind.LOADING, true, MODEL_LOADING_TTL_MILLIS
            );
        }
        if (!loadingPublished.compareAndSet(true, false)) {
            return null;
        }
        return presenceActivity.publish(activityId, LlmPresenceActivityBridge.Kind.LOADING, false, 0L);
    }

    /**
     * Tells presence about the lane itself rather than about one request: it is reconnecting, it gave
     * up, or it kept working on a reduced backend.
     *
     * <p>The TTL is a backstop for a process that dies mid-notice; a lane that recovers or shuts down
     * calls {@link #clearRuntimeActivity} instead of waiting for it.</p>
     */
    public TianshuEnvelope publishRuntimeActivity(LlmPresenceActivityBridge.Kind type, long ttlMillis) {
        String activityId = LlmPresenceActivityBridge.runtimeActivityId(type);
        activeRuntimeActivities.add(type);
        return presenceActivity.publish(activityId, type, true, Math.max(1L, ttlMillis));
    }

    /**
     * Retracts one runtime notice. Retracting a notice that was never published sends nothing, so a
     * lane that starts cleanly produces no stray end events.
     */
    public TianshuEnvelope clearRuntimeActivity(LlmPresenceActivityBridge.Kind type) {
        if (!activeRuntimeActivities.remove(type)) {
            return null;
        }
        return presenceActivity.publish(LlmPresenceActivityBridge.runtimeActivityId(type), type, false, 0L);
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

    // --- handlers --------------------------------------------------------------------------------

    public void handleLLMRequest(TianshuEnvelope envelope) {
        handleLLMRequest(envelope, null);
    }

    public void handleLLMRequest(TianshuEnvelope envelope, ProtocolContext context) {
        promptRequestHandler.handle(envelope, context);
    }

    LLMService currentLlmService() {
        return llmService;
    }

    public void handleLLMCacheManage(TianshuEnvelope envelope) {
        handleLLMCacheManage(envelope, null);
    }

    public void handleLLMCacheManage(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope == null || !(envelope.payload() instanceof LLMCacheManagePayload payload)) {
            complete(context, envelope);
            return;
        }
        LlmRagStorageService storage = ragStorageService;
        if (storage == null) {
            respondTo(
                    envelope,
                    PayloadType.of("LLM_CACHE_MANAGE_RESULT"),
                    LLMCacheManageResultPayload.failed(payload.uid(), "RAG storage is not initialized")
            );
            fail(context, envelope, "LLM_SERVICE_NOT_READY", "RAG storage is not initialized", null);
            return;
        }
        try {
            LLMCacheManageResultPayload result = switch (payload.action()) {
                case LLMCacheManagePayload.ACTION_UPSERT_ENTRY -> {
                    LlmRagStorageService.RagWriteResult write = storage.upsert(
                            payload.uid(), payload.entryId(), payload.content(), payload.vector());
                    yield write.success()
                            ? LLMCacheManageResultPayload.upserted(payload.uid(), payload.entryId())
                            : LLMCacheManageResultPayload.failed(payload.uid(), write.errorCode());
                }
                case LLMCacheManagePayload.ACTION_PATCH_ENTRY -> {
                    LlmRagStorageService.RagWriteResult write = storage.patch(
                            payload.uid(),
                            payload.entryId(),
                            payload.content(),
                            payload.vector(),
                            Boolean.TRUE.equals(payload.updateContent()),
                            Boolean.TRUE.equals(payload.updateVector())
                    );
                    yield LLMCacheManageResultPayload.patched(
                            payload.uid(),
                            payload.entryId(),
                            write.success() && storage.hasEntry(payload.uid(), payload.entryId())
                    );
                }
                case LLMCacheManagePayload.ACTION_DELETE_ENTRY -> storage.delete(payload.uid(), payload.entryId())
                        ? LLMCacheManageResultPayload.deleted(payload.uid(), payload.entryId())
                        : LLMCacheManageResultPayload.failed(payload.uid(), "RAG_DELETE_FAILED");
                case LLMCacheManagePayload.ACTION_CLEAR_UID -> storage.clear(payload.uid())
                        ? LLMCacheManageResultPayload.cleared(payload.uid())
                        : LLMCacheManageResultPayload.failed(payload.uid(), "RAG_CLEAR_FAILED");
                case LLMCacheManagePayload.ACTION_REGISTER_LIBRARY -> LLMCacheManageResultPayload.registered(
                        toLibraryPayload(storage.registerLibrary(
                                payload.uid(), payload.modid(), payload.visibility(), payload.tags()))
                );
                case LLMCacheManagePayload.ACTION_UNREGISTER_LIBRARY -> {
                    storage.unregisterLibrary(payload.uid());
                    yield LLMCacheManageResultPayload.unregistered(payload.uid());
                }
                case LLMCacheManagePayload.ACTION_QUERY_UID -> LLMCacheManageResultPayload.queried(
                        payload.uid(),
                        storage.hasCache(payload.uid()),
                        toLibraryPayload(storage.library(payload.uid()))
                );
                case LLMCacheManagePayload.ACTION_SEARCH_UID -> {
                    List<RagCacheManager.RagEntrySearchResult> entries = storage.searchEntries(
                            payload.uid(), payload.queryText(), payload.topK(), payload.threshold());
                    yield LLMCacheManageResultPayload.searched(
                            payload.action(),
                            payload.uid(),
                            entries.isEmpty() ? List.of() : List.of(toHitGroup(payload.uid(), entries)),
                            toLibraryList(storage.library(payload.uid()))
                    );
                }
                case LLMCacheManagePayload.ACTION_SEARCH_MODID -> {
                    List<LlmRagStorageService.LibrarySearchResult> results = storage.searchSharedByModid(
                            payload.modid(), payload.queryText(), payload.topK(), payload.threshold());
                    yield LLMCacheManageResultPayload.searched(
                            payload.action(), "", toStorageHitGroups(results), toStorageLibraryPayloads(results));
                }
                case LLMCacheManagePayload.ACTION_SEARCH_TAGS -> {
                    List<LlmRagStorageService.LibrarySearchResult> results = storage.searchSharedByTags(
                            payload.tags(), payload.queryText(), payload.topK(), payload.threshold());
                    yield LLMCacheManageResultPayload.searched(
                            payload.action(), "", toStorageHitGroups(results), toStorageLibraryPayloads(results));
                }
                case LLMCacheManagePayload.ACTION_SEARCH_INLINE_CONTENTS -> {
                    List<RagCacheManager.RagEntrySearchResult> entries = storage.searchInline(
                            payload.contents(), payload.queryText(), payload.topK(), payload.threshold());
                    yield LLMCacheManageResultPayload.searched(
                            payload.action(),
                            payload.uid(),
                            entries.isEmpty() ? List.of() : List.of(toHitGroup(payload.uid(), entries)),
                            List.of()
                    );
                }
                default -> LLMCacheManageResultPayload.failed(
                        payload.uid(), "Unknown action: " + payload.action());
            };
            respondTo(envelope, PayloadType.of("LLM_CACHE_MANAGE_RESULT"), result);
            complete(context, envelope);
        } catch (Exception exception) {
            respondTo(
                    envelope,
                    PayloadType.of("LLM_CACHE_MANAGE_RESULT"),
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
                        ? service.tokenCountResponse(payload.requestId(), toChatMessages(payload))
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
                        ? service.runtimeSnapshotResponse(payload.requestId())
                        : LLMPrimitiveResultPayload.runtime(payload.requestId(), unavailableRuntimeSnapshot());
                default -> LLMPrimitiveResultPayload.failed(
                        payload.requestId(),
                        payload.queryType(),
                        "UNKNOWN_QUERY_TYPE",
                        "Unknown primitive query type"
                );
            };
            respondTo(envelope, PayloadType.of("LLM_PRIMITIVE_RESULT"), result);
            complete(context, envelope);
        } catch (Exception exception) {
            respondTo(
                    envelope,
                    PayloadType.of("LLM_PRIMITIVE_RESULT"),
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

    private static List<ChatMessage> toChatMessages(LLMPrimitiveQueryPayload payload) {
        List<ChatMessage> messages = new ArrayList<>();
        if (payload.text() != null && !payload.text().isBlank()) {
            messages.add(new ChatMessage("user", payload.text()));
        }
        for (LLMPrimitiveQueryPayload.MessageItemPayload item : payload.messages()) {
            if (item != null) {
                messages.add(new ChatMessage(item.role(), item.content()));
            }
        }
        return messages;
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
                                        hit.entryId(), hit.content(), hit.score()))
                                .toList()
        );
    }

    private List<LLMCacheManageResultPayload.LibraryPayload> toLibraryList(
            RagLibraryRegistry.RagLibraryMeta meta
    ) {
        LLMCacheManageResultPayload.LibraryPayload payload = toLibraryPayload(meta);
        return payload == null ? List.of() : List.of(payload);
    }

    private LLMCacheManageResultPayload.LibraryPayload toLibraryPayload(RagLibraryRegistry.RagLibraryMeta meta) {
        return meta == null
                ? null
                : LLMCacheManageResultPayload.LibraryPayload.of(
                        meta.uid(), meta.modid(), meta.visibility(), meta.tags());
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
