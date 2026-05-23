package com.rheinmetal.tianshu.function.auxilium.fact;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

public interface RuntimeFactProvider {
    String providerId();

    default RuntimeFactBatch refreshForQuestion(AXScope scope, AXRequest request) {
        return RuntimeFactBatch.unchanged(providerId());
    }
}
