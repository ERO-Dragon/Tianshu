package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.module.TianshuManagedModule;
import com.rheinmetal.tianshu.event.TianshuEventBus;
import com.rheinmetal.tianshu.model.ModelManager;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.StreamTextPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsSpeakPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.function.Supplier;

public final class TtsModule implements TianshuManagedModule {
    private final IAudioBridge audioBridge;
    private final TianshuEventBus eventBus;
    private final ProtocolRuntime runtime;
    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final Runnable interruptProcessing;
    private final Supplier<ModelManager> modelManagerSupplier;
    private final TtsProtocolAdapter adapter;
    private TtsWorker ttsWorker;
    private TtsModelService modelService;

    public TtsModule(IAudioBridge audioBridge, TianshuEventBus eventBus, ProtocolRuntime runtime, IGameEnvironment env, ITianshuConfig config, Runnable interruptProcessing, Supplier<ModelManager> modelManagerSupplier) {
        this.audioBridge = audioBridge;
        this.eventBus = eventBus;
        this.runtime = runtime;
        this.env = env;
        this.config = config;
        this.interruptProcessing = interruptProcessing;
        this.modelManagerSupplier = modelManagerSupplier;
        this.adapter = new TtsProtocolAdapter(runtime);
    }

    @Override
    public String moduleId() {
        return "module.tts";
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        modelService = new TtsModelService(env, config, audioBridge, runtime.executors(), () -> ttsWorker, interruptProcessing, eventBus::publishEvent);
        context.services().register(TtsModelService.class, modelService);
        adapter.registerSpeakCapability(this::handleSpeak);
        adapter.subscribeLlmStream(this::handleLlmStream);
    }

    @Override
    public void prepare(ModuleRuntimeContext context) {
        ttsWorker = new TtsWorker(audioBridge, eventBus, env, config, modelService, modelManagerSupplier);
        context.services().register(TtsWorker.class, ttsWorker);
        ttsWorker.initEngine();
        boolean initialized = ttsWorker.isEngineInitialized();
        context.runtimeState().readiness().setTtsReady(initialized);
        if (initialized) {
            env.executeOnMainThread(() -> env.displayMessageToPlayer("§b[天极] §f灵音共鸣已就绪"));
        }
    }

    @Override
    public void destroy() {
        ttsWorker.stop();
    }

    private void handleSpeak(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof TtsSpeakPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "TTS payload is invalid", null);
            return;
        }
        adapter.submitTtsTask(envelope.envelopeId(), ttsWorker.currentSynthesisLane(), () -> {
            ttsWorker.speakProtocolText(payload.text(), payload.interruptCurrent());
            context.complete(envelope.envelopeId());
        });
    }

    private void handleLlmStream(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof StreamTextPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "LLM stream payload is invalid", null);
            return;
        }
        if (envelope.header().packetType() == PacketType.STREAM_END || payload.last()) {
            adapter.submitTtsTask(envelope.envelopeId(), ttsWorker.currentSynthesisLane(), ttsWorker::finishProtocolPlayback);
            return;
        }
        adapter.submitTtsTask(envelope.envelopeId(), ttsWorker.currentSynthesisLane(), () -> ttsWorker.handleProtocolChunk(payload.text()));
    }
}
