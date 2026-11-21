package com.rheinmetal.tianshu.dubhe.dialogue.gamecontext;

import com.rheinmetal.tianshu.dubhe.DubheProtocolAdapter;
import com.rheinmetal.tianshu.dubhe.DubheRequest;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheContextBudget;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheContextSnapshot;
import com.rheinmetal.tianshu.dubhe.agent.llm.DubheLlmRagClient;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.weaving.contract.LLMCacheManagePayload;
import com.rheinmetal.tianshu.weaving.contract.LLMCacheManageResultPayload;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheSharedKnowledgePlannerTest {
    @Test
    void dynamicStaticRagUsesInlineDynamicFactHits() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        RecordingCacheManageProvider provider = new RecordingCacheManageProvider();
        registerCacheManageProvider(runtime, provider);
        DubheSharedKnowledgePlanner planner = new DubheSharedKnowledgePlanner(
                new DubheLlmRagClient(new DubheProtocolAdapter(runtime), 2_000L),
                2_000L
        );
        DubheDynamicFact anvil = DubheDynamicFact.of("当前准星目标：minecraft:anvil。", 90, "test");
        DubheDynamicFact goat = DubheDynamicFact.of("附近有 minecraft:goat。", 80, "test");

        List<DubheKnowledgeHit> hits = planner.plan(
                new DubheRequest("request", "这个怎么用？", ""),
                new DubheContextSnapshot(null, null, null, List.of(anvil, goat), ""),
                DubheContextBudget.DEFAULT
        );

        assertEquals(List.of(
                        LLMCacheManagePayload.ACTION_SEARCH_TAGS,
                        LLMCacheManagePayload.ACTION_SEARCH_INLINE_CONTENTS,
                        LLMCacheManagePayload.ACTION_SEARCH_TAGS
                ),
                provider.requests.stream().map(LLMCacheManagePayload::action).toList());
        assertEquals(List.of("main", "addon"), provider.requests.get(0).tags());
        assertEquals(List.of("main", "addon"), provider.requests.get(2).tags());
        assertTrue(provider.requests.get(1).contents().contains(anvil.text()));
        assertTrue(provider.requests.get(1).contents().contains(goat.text()));
        assertTrue(provider.requests.get(2).queryText().contains("minecraft:anvil"));
        assertFalse(provider.requests.get(2).queryText().contains("minecraft:goat"));
        assertTrue(hits.stream().anyMatch(hit -> hit.queryPath() == DubheKnowledgeHit.QueryPath.DYNAMIC_FACT
                && hit.facts().equals(List.of(anvil.text()))));
        assertTrue(hits.stream().anyMatch(hit -> hit.queryPath() == DubheKnowledgeHit.QueryPath.DYNAMIC_RAG
                && hit.facts().contains("anvil dynamic static knowledge")));
    }

    private static void registerCacheManageProvider(ProtocolRuntime runtime, RecordingCacheManageProvider provider) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.registerModule(new ModuleDescriptor(
                "module.llm.cache-manage.gamecontext-test",
                List.of(new CapabilityDescriptor(
                        "LLM.CACHE_MANAGE",
                        PayloadType.of("LLM_CACHE_MANAGE"),
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
    }

    private static final class RecordingCacheManageProvider {
        private final List<LLMCacheManagePayload> requests = new ArrayList<>();

        private void handle(TianshuEnvelope envelope, ProtocolContext context) {
            LLMCacheManagePayload payload = (LLMCacheManagePayload) envelope.payload();
            requests.add(payload);
            LLMCacheManageResultPayload result = switch (payload.action()) {
                case LLMCacheManagePayload.ACTION_SEARCH_INLINE_CONTENTS -> LLMCacheManageResultPayload.searched(
                        payload.action(),
                        payload.uid(),
                        List.of(LLMCacheManageResultPayload.HitGroupPayload.of(
                                payload.uid(),
                                List.of(LLMCacheManageResultPayload.HitEntryPayload.of("0", "", 0.95D))
                        )),
                        List.of()
                );
                case LLMCacheManagePayload.ACTION_SEARCH_TAGS -> LLMCacheManageResultPayload.searched(
                        payload.action(),
                        "",
                        List.of(LLMCacheManageResultPayload.HitGroupPayload.of(
                                "mc.test",
                                List.of(LLMCacheManageResultPayload.HitEntryPayload.of(
                                        "entry",
                                        payload.tags().contains("addon") ? "anvil dynamic static knowledge" : "direct static knowledge",
                                        0.9D
                                ))
                        )),
                        List.of()
                );
                default -> LLMCacheManageResultPayload.failed(payload.uid(), "unsupported action");
            };
            context.submit(EnvelopeBuilder.responseTo(
                    "module.llm.cache-manage.gamecontext-test",
                    envelope,
                    PayloadType.of("LLM_CACHE_MANAGE_RESULT"),
                    result
            ).build());
            context.complete(envelope.envelopeId());
        }
    }
}
