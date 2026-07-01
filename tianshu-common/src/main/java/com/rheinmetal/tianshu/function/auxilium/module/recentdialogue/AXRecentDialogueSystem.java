package com.rheinmetal.tianshu.function.auxilium.module.recentdialogue;

import com.rheinmetal.tianshu.function.auxilium.core.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurnBatch;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurnWindow;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.util.List;

public final class AXRecentDialogueSystem {
    private final AXRawTurnWindow rawTurnWindow;

    public AXRecentDialogueSystem(AXMemoryWindowPolicy policy) {
        this.rawTurnWindow = new AXRawTurnWindow(policy);
    }

    public void append(AXScope scope, AXRawTurn turn) {
        rawTurnWindow.append(scope, turn);
    }

    public AXRecentDialogueSnapshot snapshot(AXScope scope) {
        return new AXRecentDialogueSnapshot(rawTurnWindow.recent(scope));
    }

    public AXRawTurnBatch selectCompressionBatch(AXScope scope) {
        return rawTurnWindow.selectCompressionBatch(scope);
    }

    public int confirmConsumed(AXScope scope, AXRawTurnBatch batch) {
        if (batch == null || batch.isEmpty()) {
            return 0;
        }
        List<String> turnIds = batch.turnIds();
        if (turnIds.isEmpty()) {
            return 0;
        }
        return rawTurnWindow.confirmConsumed(scope, turnIds);
    }
}
