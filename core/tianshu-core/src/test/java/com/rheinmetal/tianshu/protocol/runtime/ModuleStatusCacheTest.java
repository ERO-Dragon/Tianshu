package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import com.rheinmetal.tianshu.protocol.status.ModuleStatusKeys;
import com.rheinmetal.tianshu.protocol.status.ModuleStatusSeverity;
import com.rheinmetal.tianshu.protocol.status.ModuleStatuses;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleStatusCacheTest {
    @Test
    void lifecycleReadyRemainsAfterOperationStatusExpires() throws Exception {
        ModuleStatusCache cache = new ModuleStatusCache();
        cache.accept(ModuleStatuses.lifecycleReady("module.test", 2L));
        cache.accept(new ModuleStatus("module.test", "runtime.operation", "operation.ready",
                ModuleStatusSeverity.NOTICE, System.currentTimeMillis() - 100L, 1L, Map.of()));

        assertTrue(cache.latestLifecycle("module.test").isPresent());
        assertEquals(1, cache.byModule("module.test").size());
    }

    @Test
    void lifecycleGenerationRejectsOlderUpdates() {
        ModuleStatusCache cache = new ModuleStatusCache();
        cache.accept(ModuleStatuses.lifecycleStopped("module.test", 4L));
        cache.accept(ModuleStatuses.lifecycleReady("module.test", 3L));

        // The key is derived from the module id now, so the assertion reads it back through the same
        // source instead of repeating a literal.
        assertEquals(ModuleStatusKeys.stopped("module.test"),
                cache.latestLifecycle("module.test").orElseThrow().messageKey());
    }

    @Test
    void operationStatusesRemainIndependentByType() {
        ModuleStatusCache cache = new ModuleStatusCache();
        cache.accept(ModuleStatuses.readyKeyed("module.test", "operation.ready"));
        cache.accept(ModuleStatuses.waitingKeyed("module.test", "operation.waiting"));

        assertTrue(cache.latest("module.test", ModuleStatuses.TYPE_READY).isPresent());
        assertTrue(cache.latest("module.test", ModuleStatuses.TYPE_WAITING).isPresent());
    }
}
