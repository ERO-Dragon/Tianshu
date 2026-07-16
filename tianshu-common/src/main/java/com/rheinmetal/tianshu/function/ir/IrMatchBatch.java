package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.function.ir.input.IrInputText;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerMatch;

import java.util.List;

public record IrMatchBatch(IrInputText input, List<VoiceTriggerMatch> matches) {
    public IrMatchBatch {
        if (matches == null || matches.isEmpty()) {
            matches = List.of();
        } else {
            matches = List.copyOf(matches);
        }
    }

    public boolean matched() {
        return !matches.isEmpty();
    }
}
