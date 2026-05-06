package com.rheinmetal.tianshu.function.asr;

import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.AsrTextPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
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
}
