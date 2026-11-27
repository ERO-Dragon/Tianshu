package com.rheinmetal.tianshu.core.lifecycle;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.EntitySpawnPort;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A module that stops takes its bodies with it, and only its own. */
class ModuleHostWithdrawsBodiesTest {

    private final RecordingPort port = new RecordingPort();

    @AfterEach
    void clean() {
        TianshuRuntimePorts.unregister(EntitySpawnPort.class, port);
    }

    @Test
    void stoppingAModuleWithdrawsItsBodies() {
        TianshuRuntimePorts.register(EntitySpawnPort.class, port);
        TianshuModuleHost host = new TianshuModuleHost(null);
        RecordingModule module = new RecordingModule("module.a");
        host.registerOptionalModule(module);
        host.registerAll(null);
        host.prepareAll(null);
        host.startAll(null);

        host.stopAll();

        assertEquals(List.of("module.a"), port.withdrawn, "the stopping module's id is the one withdrawn");
        assertTrue(module.stopped, "the module was stopped as well");
    }

    @Test
    void aModuleThatNeverStartedIsNotWithdrawn() {
        TianshuRuntimePorts.register(EntitySpawnPort.class, port);
        TianshuModuleHost host = new TianshuModuleHost(null);
        host.registerOptionalModule(new RecordingModule("module.b"));
        host.registerAll(null);

        host.stopAll();

        assertEquals(List.of(), port.withdrawn, "nothing was started, so nothing is stopped or withdrawn");
    }

    private record Withdrawn(String moduleId) {
    }

    private static final class RecordingPort implements EntitySpawnPort {

        private final List<String> withdrawn = new ArrayList<>();

        @Override
        public CompletableFuture<com.rheinmetal.tianshu.api.entity.EntityActor> spawn(String moduleId, String kind,
                com.rheinmetal.tianshu.api.entity.ActorPosition position, boolean playerLike) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("not needed here"));
        }

        @Override
        public CompletableFuture<Void> release(String moduleId, String bodyId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void withdrawAll(String moduleId) {
            withdrawn.add(moduleId);
        }
    }

    private static final class RecordingModule implements TianshuManagedModule {

        private final String id;
        private boolean stopped;

        private RecordingModule(String id) {
            this.id = id;
        }

        @Override
        public String moduleId() {
            return id;
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }
}