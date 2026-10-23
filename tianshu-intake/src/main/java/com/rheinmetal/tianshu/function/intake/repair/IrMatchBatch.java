package com.rheinmetal.tianshu.function.intake.repair;

import com.rheinmetal.tianshu.function.intake.repair.input.IrInputText;
import com.rheinmetal.tianshu.protocol.payload.IrModuleMatch;

import java.util.List;

/**
 * One repair pass: the text that was matched and the module trigger words it hit.
 *
 * <p>One entry per hit word rather than one entry per module. The old shape grouped a module's hits into two word
 * lists plus a confidence, which threw away the pairing between what the player said and which registered word it
 * hit; every consumer that needed the pairing had to reconstruct it from the aggregate.</p>
 */
public record IrMatchBatch(IrInputText input, List<IrModuleMatch> matches) {
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
