package com.rheinmetal.tianshu.function.asr;

import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.AsrTextPayload;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;

public final class AsrProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = "module.asr";
    public static final String SOURCE_ID = "module.asr";

    public AsrProtocolAdapter(ProtocolRuntime runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.standard());
    }

    public TianshuEnvelope publishFinalText(AsrTextPayload payload) {
        return publishTopic(ProtocolTopics.INPUT_ASR_FINAL_TEXT, PayloadType.ASR_TEXT, payload);
    }

    public ProtocolTaskHandle submitStreamProcessor(Runnable task) {
        return submitTask(
                taskSpec(ExecutionLane.ASR_STREAM)
                        .concurrencyKey(MODULE_ID + ":stream.processor")
                        .maxConcurrency(1)
                        .queueCapacity(1)
                        .build(),
                task
        );
    }
}
