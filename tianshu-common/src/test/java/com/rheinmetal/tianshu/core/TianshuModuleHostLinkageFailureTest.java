package com.rheinmetal.tianshu.core;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.core.lifecycle.status.ModuleLifecycleState;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TianshuModuleHostLinkageFailureTest {
    @Test
    void requiredLinkageFailureStillFailsAndRetainsTheCause() {
        var host = new TianshuModuleHost(new TestLlmSupport.FakeGameEnvironment());
        host.registerRequiredModule(new FailingModule());
        host.registerAll(null);
        var failure = assertThrows(com.rheinmetal.tianshu.core.lifecycle.ModuleLifecycleException.class,
                () -> host.prepareAll(null));
        assertInstanceOf(LinkageError.class, failure.getCause());
    }

    @Test
    void optionalDoesNotSwallowFatalVmErrors() {
        var host = new TianshuModuleHost(new TestLlmSupport.FakeGameEnvironment());
        host.registerOptionalModule(new TianshuManagedModule() {
            public String moduleId() { return "fatal"; }
            public void prepare(com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext context) {
                throw new OutOfMemoryError("simulated, no allocation");
            }
        });
        host.registerAll(null);
        assertThrows(OutOfMemoryError.class, () -> host.prepareAll(null));
    }

    @Test
    void nativeCleanupFailureDoesNotSkipOtherModules() {
        var host = new TianshuModuleHost(new TestLlmSupport.FakeGameEnvironment());
        var stopped = new java.util.concurrent.atomic.AtomicBoolean();
        host.registerOptionalModule(new TianshuManagedModule() {
            public String moduleId() { return "healthy"; }
            public void stop() { stopped.set(true); }
        });
        host.registerOptionalModule(new TianshuManagedModule() {
            public String moduleId() { return "native"; }
            public void stop() { throw new UnsatisfiedLinkError("simulated cleanup failure"); }
        });
        host.registerAll(null);
        host.prepareAll(null);
        host.startAll(null);
        assertDoesNotThrow(host::stopAll);
        assertTrue(stopped.get());
    }

    @Test
    void optionalLinkageFailureDoesNotAbortUnrelatedModulePreparation() {
        TianshuModuleHost host = new TianshuModuleHost(new TestLlmSupport.FakeGameEnvironment());
        host.registerOptionalModule(new FailingModule());
        host.registerOptionalModule(new HealthyModule());

        host.registerAll(null);
        host.prepareAll(null);

        assertEquals(ModuleLifecycleState.FAILED, host.moduleStatuses().stream()
                .filter(status -> status.moduleId().equals("module.test.linkage"))
                .findFirst().orElseThrow().state());
        assertEquals(ModuleLifecycleState.PREPARED, host.moduleStatuses().stream()
                .filter(status -> status.moduleId().equals("module.test.healthy"))
                .findFirst().orElseThrow().state());
    }

    private static final class FailingModule implements TianshuManagedModule {
        @Override public String moduleId() { return "module.test.linkage"; }
        @Override public void prepare(com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext context) {
            throw new LinkageError("simulated native load failure");
        }
    }

    private static final class HealthyModule implements TianshuManagedModule {
        @Override public String moduleId() { return "module.test.healthy"; }
    }
}
