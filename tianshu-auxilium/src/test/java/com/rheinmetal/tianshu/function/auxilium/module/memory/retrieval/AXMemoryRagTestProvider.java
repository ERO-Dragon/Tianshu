package com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.LLMCacheManagePayload;
import com.rheinmetal.tianshu.protocol.payload.LLMCacheManageResultPayload;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AXMemoryRagTestProvider {
    private static final String MODULE_ID = "module.llm.rag.memory-test";
    private final Map<String, Map<String, Entry>> entriesByUid = new LinkedHashMap<>();
    private final float[] queryVector;

    private AXMemoryRagTestProvider(float[] queryVector) {
        this.queryVector = queryVector == null ? new float[]{1.0F, 0.0F} : queryVector.clone();
    }

    static AXMemoryRagTestProvider register(ProtocolRuntime runtime) {
        AXMemoryRagTestProvider provider = new AXMemoryRagTestProvider(new float[]{1.0F, 0.0F});
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.registerModule(new ModuleDescriptor(
                MODULE_ID,
                List.of(new CapabilityDescriptor(
                        ProtocolCapabilities.LLM_CACHE_MANAGE,
                        PayloadType.LLM_CACHE_MANAGE,
                        LLMCacheManagePayload.class,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.REQUEST),
                        Priority.LOW,
                        CompletionPolicy.MANUAL_COMPLETE
                )),
                defaults.threadPolicy(),
                defaults.cancellationScope(),
                defaults.failurePolicy(),
                defaults.deliveryPolicy(),
                defaults.cancellable(),
                defaults.supportsStreaming(),
                defaults.maxConcurrency(),
                defaults.queueCapacity()
        ), provider::handle);
        return provider;
    }

    private void handle(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof LLMCacheManagePayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "invalid", null);
            return;
        }
        LLMCacheManageResultPayload result = switch (payload.action()) {
            case LLMCacheManagePayload.ACTION_REGISTER_LIBRARY -> LLMCacheManageResultPayload.registered(
                    LLMCacheManageResultPayload.LibraryPayload.of(payload.uid(), payload.modid(), payload.visibility(), payload.tags())
            );
            case LLMCacheManagePayload.ACTION_CLEAR_UID -> {
                entriesByUid.put(payload.uid(), new LinkedHashMap<>());
                yield LLMCacheManageResultPayload.cleared(payload.uid());
            }
            case LLMCacheManagePayload.ACTION_UPSERT_ENTRY -> {
                entriesByUid.computeIfAbsent(payload.uid(), ignored -> new LinkedHashMap<>())
                        .put(payload.entryId(), new Entry(payload.entryId(), payload.content(), payload.vector()));
                yield LLMCacheManageResultPayload.upserted(payload.uid(), payload.entryId());
            }
            case LLMCacheManagePayload.ACTION_SEARCH_UID -> search(payload);
            default -> LLMCacheManageResultPayload.failed(payload.uid(), "unsupported action");
        };
        context.submit(EnvelopeBuilder.responseTo(MODULE_ID, envelope, PayloadType.LLM_CACHE_MANAGE_RESULT, result).build());
        context.complete(envelope.envelopeId());
    }

    private LLMCacheManageResultPayload search(LLMCacheManagePayload payload) {
        Map<String, Entry> entries = entriesByUid.getOrDefault(payload.uid(), Map.of());
        List<LLMCacheManageResultPayload.HitEntryPayload> hits = new ArrayList<>();
        for (Entry entry : entries.values()) {
            double score = cosine(queryVector, entry.vector());
            if (score >= payload.threshold()) {
                hits.add(LLMCacheManageResultPayload.HitEntryPayload.of(entry.entryId(), entry.content(), score));
            }
        }
        hits = hits.stream()
                .sorted(Comparator.comparingDouble(LLMCacheManageResultPayload.HitEntryPayload::score).reversed())
                .limit(payload.topK())
                .toList();
        return LLMCacheManageResultPayload.searched(
                payload.action(),
                payload.uid(),
                hits.isEmpty() ? List.of() : List.of(LLMCacheManageResultPayload.HitGroupPayload.of(payload.uid(), hits)),
                List.of()
        );
    }

    private double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || right.length == 0 || left.length != right.length) {
            return 0.0D;
        }
        double dot = 0.0D;
        double leftNorm = 0.0D;
        double rightNorm = 0.0D;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        return leftNorm <= 0.0D || rightNorm <= 0.0D ? 0.0D : dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private record Entry(String entryId, String content, float[] vector) {
        private Entry {
            entryId = entryId == null ? "" : entryId.trim();
            content = content == null ? "" : content;
            vector = vector == null ? new float[0] : vector.clone();
        }
    }
}
