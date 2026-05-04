package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.event.AsrFinalTextEvent;
import com.rheinmetal.tianshu.event.LlmChunkEvent;
import com.rheinmetal.tianshu.event.LlmEndEvent;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CancellationScope;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.FailurePolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;
import com.rheinmetal.tianshu.protocol.payload.AlertThreatPayload;
import com.rheinmetal.tianshu.protocol.payload.FeedbackPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmPromptPayload;
import com.rheinmetal.tianshu.protocol.payload.TextPayload;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;

import java.util.EnumSet;
import java.util.List;

public final class ProtocolBootstrap {
    private ProtocolBootstrap() {
    }

    public static ProtocolRuntime create(TianshuCoreManager coreManager, IGameEnvironment env, IAudioBridge audioBridge, MainThreadExecutor mainThreadExecutor) {
        ProtocolRuntime runtime = new ProtocolRuntime(mainThreadExecutor);
        registerTopics(runtime);
        registerCoreAdapters(runtime, coreManager, env, audioBridge);
        return runtime;
    }

    private static void registerTopics(ProtocolRuntime runtime) {
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.ASR_FINAL_TEXT, PayloadType.ASR_TEXT, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.LLM_STREAM, PayloadType.LLM_TEXT_CHUNK, DeliveryPolicy.WAIT_IN_QUEUE, 200));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.UI_STATUS, PayloadType.TEXT, DeliveryPolicy.WAIT_IN_QUEUE, 60));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.DEBUG_TRACE, PayloadType.CUSTOM, DeliveryPolicy.WAIT_IN_QUEUE, 30));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.HOVER_STATE, PayloadType.SNAPSHOT, DeliveryPolicy.LATEST_ONLY, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.CROSSHAIR_STATE, PayloadType.SNAPSHOT, DeliveryPolicy.LATEST_ONLY, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.TICK_STATE, PayloadType.SNAPSHOT, DeliveryPolicy.LATEST_ONLY, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.ALERT_THREAT, PayloadType.ALERT, DeliveryPolicy.WAIT_IN_QUEUE, 40));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.ALERT_CLEARED, PayloadType.ALERT, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.SYSTEM_DANGER_MODE_CHANGED, PayloadType.SYSTEM_STATE, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.INPUT_ASR_FINAL_TEXT, PayloadType.ASR_TEXT, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.INPUT_KEY_ACTION, PayloadType.INPUT_ACTION, DeliveryPolicy.WAIT_IN_QUEUE, 60));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.INPUT_CHAT_TEXT, PayloadType.TEXT, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.INPUT_HOVER_ITEM, PayloadType.SNAPSHOT, DeliveryPolicy.LATEST_ONLY, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.ITEM_HOVER_STABLE, PayloadType.SNAPSHOT, DeliveryPolicy.LATEST_ONLY, 10));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.ITEM_HOVER_CLEARED, PayloadType.NONE, DeliveryPolicy.LATEST_ONLY, 10));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.ITEM_ANALYSIS_READY, PayloadType.CUSTOM, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.FEEDBACK_EMIT, PayloadType.FEEDBACK, DeliveryPolicy.WAIT_IN_QUEUE, 80));
    }

    private static void registerCoreAdapters(ProtocolRuntime runtime, TianshuCoreManager coreManager, IGameEnvironment env, IAudioBridge audioBridge) {
        registerLlmBridge(runtime, coreManager);
        registerTtsSpeakBridge(runtime, coreManager, env, audioBridge);
        registerTtsAlertBridge(runtime, coreManager, env, audioBridge);
        registerFeedbackFlow(runtime);
        registerIrPassthrough(runtime);
    }

    private static void registerLlmBridge(ProtocolRuntime runtime, TianshuCoreManager coreManager) {
        CapabilityDescriptor descriptor = new CapabilityDescriptor(
            ProtocolCapabilities.LLM_CHAT,
            PayloadType.LLM_PROMPT,
            LlmPromptPayload.class,
            BrokerType.PARALLEL_LIMIT,
            EnumSet.of(PacketType.REQUEST, PacketType.COMMAND),
            Priority.LOW
        );
        ModuleDescriptor module = new ModuleDescriptor("core.llm.legacy.bridge", List.of(descriptor), ThreadPolicy.IO_BLOCKING, CancellationScope.TRACE, FailurePolicy.PROPAGATE_CANCEL, DeliveryPolicy.WAIT_IN_QUEUE, true, true, 1, 64);
        runtime.registerModule(module, (envelope, context) -> {
            LlmPromptPayload payload = (LlmPromptPayload) envelope.payload();
            long sessionId = parseSessionId(envelope.traceId());
            coreManager.getEventBus().publishEvent(new AsrFinalTextEvent(payload.text(), 1, sessionId));
        });
    }

    private static void registerTtsSpeakBridge(ProtocolRuntime runtime, TianshuCoreManager coreManager, IGameEnvironment env, IAudioBridge audioBridge) {
        CapabilityDescriptor descriptor = new CapabilityDescriptor(
            ProtocolCapabilities.TTS_SPEAK,
            PayloadType.TTS_TEXT,
            TextPayload.class,
            BrokerType.EXCLUSIVE_INTERRUPT,
            EnumSet.of(PacketType.COMMAND, PacketType.STREAM_CHUNK, PacketType.STREAM_END),
            Priority.LOW
        );
        ModuleDescriptor module = new ModuleDescriptor("core.tts.speak.legacy.bridge", List.of(descriptor), ThreadPolicy.IO_BLOCKING, CancellationScope.RESOURCE, FailurePolicy.REPORT_ONLY, DeliveryPolicy.WAIT_IN_QUEUE, true, true, 1, 16);
        runtime.registerModule(module, (envelope, context) -> {
            TextPayload payload = (TextPayload) envelope.payload();
            long sessionId = parseSessionId(envelope.traceId());
            if (envelope.header().packetType() == PacketType.STREAM_END) {
                coreManager.getEventBus().publishEvent(new LlmEndEvent(1, sessionId, false, null));
            } else {
                coreManager.getEventBus().publishEvent(new LlmChunkEvent(payload.text(), 1, sessionId));
            }
        });
    }

    private static void registerTtsAlertBridge(ProtocolRuntime runtime, TianshuCoreManager coreManager, IGameEnvironment env, IAudioBridge audioBridge) {
        CapabilityDescriptor descriptor = new CapabilityDescriptor(
            ProtocolCapabilities.TTS_ALERT,
            PayloadType.TTS_TEXT,
            TextPayload.class,
            BrokerType.EXCLUSIVE_INTERRUPT,
            EnumSet.of(PacketType.COMMAND),
            Priority.NORMAL,
            CompletionPolicy.MANUAL_COMPLETE
        );
        ModuleDescriptor module = new ModuleDescriptor("core.tts.alert.handler", List.of(descriptor), ThreadPolicy.IO_BLOCKING, CancellationScope.RESOURCE, FailurePolicy.REPORT_ONLY, DeliveryPolicy.WAIT_IN_QUEUE, true, false, 1, 8);
        runtime.registerModule(module, (envelope, context) -> {
            TextPayload payload = (TextPayload) envelope.payload();
            var ttsWorker = coreManager.getTtsWorker();
            var bridge = coreManager.getAudioBridge();
            if (ttsWorker == null || !ttsWorker.isEngineInitialized()) {
                context.fail(envelope.envelopeId(), "TTS_ENGINE_NOT_READY", "TTS engine is not initialized", null);
                return;
            }
            context.onCancel(envelope.envelopeId(), cancelled -> {
                ttsWorker.interruptSynthesis();
                bridge.stopTtsPlayback();
            });
            if (envelope.header().priority().atLeast(Priority.CRITICAL)) {
                coreManager.interruptOngoingProcessing();
            }
            try {
                env.info("[战术雷达] 枢机TTS播报: " + payload.text());
                bridge.startTtsPlayback(ttsWorker.getSampleRate());
                ttsWorker.synthesizeForPreview(payload.text(), 1.2f, audio -> {
                    if (!context.isCancelled(envelope.envelopeId())) {
                        bridge.feedTtsAudio(audio);
                    }
                });
                if (context.isCancelled(envelope.envelopeId())) {
                    bridge.stopTtsPlayback();
                    return;
                }
                bridge.finishTtsPlayback();
                context.complete(envelope.envelopeId());
            } catch (Exception exception) {
                bridge.stopTtsPlayback();
                context.fail(envelope.envelopeId(), "TTS_ALERT_FAILED", exception.getMessage(), exception);
            }
        });
    }

    private static void registerFeedbackFlow(ProtocolRuntime runtime) {
        TopicSubscriptionDescriptor descriptor = new TopicSubscriptionDescriptor(
            ProtocolTopics.ALERT_THREAT,
            PayloadType.ALERT,
            AlertThreatPayload.class,
            BrokerType.STATELESS_FAST_PATH,
            EnumSet.of(PacketType.EVENT),
            Priority.LOW,
            CompletionPolicy.AUTO_COMPLETE_ON_RETURN
        );
        ModuleDescriptor module = new ModuleDescriptor("core.feedback.alert.flow", List.of(), ThreadPolicy.ASYNC_WORKER, CancellationScope.SELF_ONLY, FailurePolicy.REPORT_ONLY, DeliveryPolicy.WAIT_IN_QUEUE, true, false, Runtime.getRuntime().availableProcessors(), 128);
        runtime.subscribeTopic(module, descriptor, (envelope, context) -> {
            AlertThreatPayload payload = (AlertThreatPayload) envelope.payload();
            Priority priority = payload.interrupt() ? Priority.CRITICAL : payload.level();
            context.submit(com.rheinmetal.tianshu.protocol.EnvelopeBuilder.childOf(envelope)
                .sourceId("core.feedback.alert.flow")
                .targetMode(com.rheinmetal.tianshu.protocol.TargetMode.TOPIC)
                .target(ProtocolTopics.FEEDBACK_EMIT)
                .packetType(PacketType.EVENT)
                .payloadType(PayloadType.FEEDBACK)
                .priority(priority)
                .payload(new FeedbackPayload(payload.text(), priority, payload.interrupt(), "tts", payload.source()))
                .build());
            context.submit(com.rheinmetal.tianshu.protocol.EnvelopeBuilder.childOf(envelope)
                .sourceId("core.feedback.alert.flow")
                .targetMode(com.rheinmetal.tianshu.protocol.TargetMode.CAPABILITY)
                .target(ProtocolCapabilities.TTS_ALERT)
                .packetType(PacketType.COMMAND)
                .payloadType(PayloadType.TTS_TEXT)
                .priority(priority)
                .payload(new TextPayload(payload.text()))
                .build());
        });
    }

    private static void registerIrPassthrough(ProtocolRuntime runtime) {
        CapabilityDescriptor descriptor = new CapabilityDescriptor(
            ProtocolCapabilities.IR_PARSE,
            PayloadType.TEXT,
            TextPayload.class,
            BrokerType.STATELESS_FAST_PATH,
            EnumSet.of(PacketType.REQUEST, PacketType.COMMAND),
            Priority.BACKGROUND
        );
        ModuleDescriptor module = new ModuleDescriptor("core.ir.passthrough", List.of(descriptor), ThreadPolicy.ASYNC_WORKER, CancellationScope.SELF_ONLY, FailurePolicy.REPORT_ONLY, DeliveryPolicy.FIRE_AND_FORGET, true, false, Runtime.getRuntime().availableProcessors(), 1024);
        runtime.registerModule(module, (envelope, context) -> {
            TextPayload payload = (TextPayload) envelope.payload();
            context.submit(com.rheinmetal.tianshu.protocol.EnvelopeBuilder.childOf(envelope)
                .sourceId("core.ir.passthrough")
                .targetMode(com.rheinmetal.tianshu.protocol.TargetMode.CAPABILITY)
                .target(ProtocolCapabilities.LLM_CHAT)
                .packetType(PacketType.REQUEST)
                .payloadType(PayloadType.LLM_PROMPT)
                .payload(new LlmPromptPayload(payload.text(), ""))
                .build());
        });
    }

    private static long parseSessionId(String traceId) {
        try {
            return Long.parseLong(traceId);
        } catch (Exception ignored) {
            return 0L;
        }
    }
}

