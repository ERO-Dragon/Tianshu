package com.rheinmetal.tianshu.core;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.core.runtime.CoreLifecyclePhase;
import com.rheinmetal.tianshu.core.runtime.CoreRuntimeStatus;
import com.rheinmetal.tianshu.core.runtime.RuntimeCapability;
import com.rheinmetal.tianshu.core.runtime.RuntimeCapabilityStatus;
import com.rheinmetal.tianshu.core.runtime.RuntimeEnginePhase;
import com.rheinmetal.tianshu.core.runtime.RuntimeRefreshReason;
import com.rheinmetal.tianshu.core.lifecycle.status.ModuleLifecycleState;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TianshuCoreManagerLifecycleTest {
    @TempDir
    Path tempDir;

    @Test
    void worldSessionLifecycleRunsOffCallerAndRebuildsDestroyedModules() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        List<String> callbackThreads = new CopyOnWriteArrayList<>();
        AtomicInteger moduleIds = new AtomicInteger();
        TianshuCoreManager core = new TianshuCoreManager(
                new TestLlmSupport.FakeGameEnvironment(),
                new TestLlmSupport.FakeConfig(tempDir),
                new NoopAudioBridge(),
                context -> (moduleHost, moduleServices) -> {
                    moduleHost.clear();
                    moduleServices.clear();
                    moduleHost.registerOptionalModule(new RecordingModule(
                            moduleIds.incrementAndGet(),
                            calls,
                            callbackThreads
                    ));
                }
        );
        String callerThread = Thread.currentThread().getName();

        CoreRuntimeStatus firstStart = core.startRuntimeSession().get(5, TimeUnit.SECONDS);
        CoreRuntimeStatus stop = core.stopRuntimeSession().get(5, TimeUnit.SECONDS);
        CoreRuntimeStatus secondStart = core.startRuntimeSession().get(5, TimeUnit.SECONDS);

        assertEquals(CoreLifecyclePhase.RUNNING, firstStart.corePhase());
        assertEquals(CoreLifecyclePhase.CREATED, stop.corePhase());
        assertEquals(CoreLifecyclePhase.RUNNING, secondStart.corePhase());
        assertEquals(RuntimeEnginePhase.FULLY_READY, secondStart.enginePhase());
        assertEquals(List.of(
                "1:register",
                "1:prepare",
                "1:start",
                "1:stop",
                "1:destroy",
                "2:register",
                "2:prepare",
                "2:start"
        ), calls);
        assertFalse(callbackThreads.isEmpty());
        assertTrue(callbackThreads.stream().allMatch(name -> name.startsWith("Tianshu-Core-Lifecycle")));
        assertTrue(callbackThreads.stream().noneMatch(callerThread::equals));

        core.destroy().get(5, TimeUnit.SECONDS);
    }

    @Test
    void emptyCoreCanRestartWorldSessionAndDestroyIsTerminalAndIdempotent() throws Exception {
        TianshuCoreManager core = new TianshuCoreManager(
                new TestLlmSupport.FakeGameEnvironment(),
                new TestLlmSupport.FakeConfig(tempDir),
                new NoopAudioBridge()
        );

        assertEquals(CoreLifecyclePhase.RUNNING, core.startRuntimeSession().get(5, TimeUnit.SECONDS).corePhase());
        assertEquals(CoreLifecyclePhase.CREATED, core.stopRuntimeSession().get(5, TimeUnit.SECONDS).corePhase());
        assertEquals(CoreLifecyclePhase.RUNNING, core.startRuntimeSession().get(5, TimeUnit.SECONDS).corePhase());

        CompletableFuture<CoreRuntimeStatus> firstDestroy = core.destroy();
        CompletableFuture<CoreRuntimeStatus> secondDestroy = core.destroy();

        assertSame(firstDestroy, secondDestroy);
        assertEquals(CoreLifecyclePhase.DESTROYED, firstDestroy.get(5, TimeUnit.SECONDS).corePhase());
        assertEquals(CoreLifecyclePhase.DESTROYED, secondDestroy.get(5, TimeUnit.SECONDS).corePhase());
        assertEquals(CoreLifecyclePhase.DESTROYED, core.startRuntimeSession().get(5, TimeUnit.SECONDS).corePhase());
        assertEquals(
                CoreLifecyclePhase.DESTROYED,
                core.refreshRuntime(RuntimeRefreshReason.MANUAL).get(5, TimeUnit.SECONDS).corePhase()
        );
    }

    @Test
    void queuedRefreshDoesNotRebuildAfterTerminalDestroyIsRequested() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        AtomicInteger assemblyCount = new AtomicInteger();
        CountDownLatch prepareEntered = new CountDownLatch(1);
        CountDownLatch releasePrepare = new CountDownLatch(1);
        TianshuCoreManager core = new TianshuCoreManager(
                new TestLlmSupport.FakeGameEnvironment(),
                new TestLlmSupport.FakeConfig(tempDir),
                new NoopAudioBridge(),
                context -> (moduleHost, moduleServices) -> {
                    moduleHost.clear();
                    moduleServices.clear();
                    int id = assemblyCount.incrementAndGet();
                    moduleHost.registerOptionalModule(new BlockingRecordingModule(
                            id,
                            calls,
                            prepareEntered,
                            releasePrepare
                    ));
                }
        );

        CompletableFuture<CoreRuntimeStatus> start = core.startRuntimeSession();
        assertTrue(prepareEntered.await(5, TimeUnit.SECONDS));
        CompletableFuture<CoreRuntimeStatus> refresh = core.refreshRuntime(RuntimeRefreshReason.RESOURCE_CHANGED);
        CompletableFuture<CoreRuntimeStatus> destroy = core.destroy();
        releasePrepare.countDown();

        start.get(5, TimeUnit.SECONDS);
        refresh.get(5, TimeUnit.SECONDS);
        assertEquals(CoreLifecyclePhase.DESTROYED, destroy.get(5, TimeUnit.SECONDS).corePhase());
        assertEquals(1, assemblyCount.get());
        assertEquals(1, calls.stream().filter("1:stop"::equals).count());
        assertEquals(1, calls.stream().filter("1:destroy"::equals).count());
    }

    @Test
    void fastWorldExitAndReentryAreSerializedAroundInFlightStart() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        AtomicInteger assemblyCount = new AtomicInteger();
        CountDownLatch firstPrepareEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstPrepare = new CountDownLatch(1);
        TianshuCoreManager core = new TianshuCoreManager(
                new TestLlmSupport.FakeGameEnvironment(),
                new TestLlmSupport.FakeConfig(tempDir),
                new NoopAudioBridge(),
                context -> (moduleHost, moduleServices) -> {
                    moduleHost.clear();
                    moduleServices.clear();
                    int id = assemblyCount.incrementAndGet();
                    TianshuManagedModule module = id == 1
                            ? new BlockingRecordingModule(id, calls, firstPrepareEntered, releaseFirstPrepare)
                            : new SimpleRecordingModule(id, calls);
                    moduleHost.registerOptionalModule(module);
                }
        );

        CompletableFuture<CoreRuntimeStatus> firstStart = core.startRuntimeSession();
        assertTrue(firstPrepareEntered.await(5, TimeUnit.SECONDS));
        CompletableFuture<CoreRuntimeStatus> stop = core.stopRuntimeSession();
        CompletableFuture<CoreRuntimeStatus> secondStart = core.startRuntimeSession();
        releaseFirstPrepare.countDown();

        firstStart.get(5, TimeUnit.SECONDS);
        assertEquals(CoreLifecyclePhase.CREATED, stop.get(5, TimeUnit.SECONDS).corePhase());
        assertEquals(CoreLifecyclePhase.RUNNING, secondStart.get(5, TimeUnit.SECONDS).corePhase());
        assertEquals(List.of(
                "1:register",
                "1:prepare",
                "1:start",
                "1:stop",
                "1:destroy",
                "2:register",
                "2:prepare",
                "2:start"
        ), calls);

        core.destroy().get(5, TimeUnit.SECONDS);
    }

    @Test
    void duplicateRefreshRequestsShareOneInFlightLifecycleCommand() throws Exception {
        AtomicInteger assemblyCount = new AtomicInteger();
        CountDownLatch refreshPrepareEntered = new CountDownLatch(1);
        CountDownLatch releaseRefreshPrepare = new CountDownLatch(1);
        TianshuCoreManager core = new TianshuCoreManager(
                new TestLlmSupport.FakeGameEnvironment(),
                new TestLlmSupport.FakeConfig(tempDir),
                new NoopAudioBridge(),
                context -> (moduleHost, moduleServices) -> {
                    moduleHost.clear();
                    moduleServices.clear();
                    int id = assemblyCount.incrementAndGet();
                    TianshuManagedModule module = id == 2
                            ? new BlockingRecordingModule(id, new CopyOnWriteArrayList<>(), refreshPrepareEntered, releaseRefreshPrepare)
                            : new SimpleRecordingModule(id, new CopyOnWriteArrayList<>());
                    moduleHost.registerOptionalModule(module);
                }
        );
        core.startRuntimeSession().get(5, TimeUnit.SECONDS);

        CompletableFuture<CoreRuntimeStatus> firstRefresh = core.refreshRuntime(RuntimeRefreshReason.RESOURCE_CHANGED);
        assertTrue(refreshPrepareEntered.await(5, TimeUnit.SECONDS));
        CompletableFuture<CoreRuntimeStatus> secondRefresh = core.refreshRuntime(RuntimeRefreshReason.RESTART_REQUESTED);

        assertSame(firstRefresh, secondRefresh);
        releaseRefreshPrepare.countDown();
        assertEquals(CoreLifecyclePhase.RUNNING, firstRefresh.get(5, TimeUnit.SECONDS).corePhase());
        assertEquals(2, assemblyCount.get());

        core.destroy().get(5, TimeUnit.SECONDS);
    }

    @Test
    void requiredPrepareFailureRemainsVisibleAfterCleanup() throws Exception {
        RuntimeCapability capability = RuntimeCapability.of("capability.test.required");
        TianshuCoreManager core = new TianshuCoreManager(
                new TestLlmSupport.FakeGameEnvironment(),
                new TestLlmSupport.FakeConfig(tempDir),
                new NoopAudioBridge(),
                context -> (moduleHost, moduleServices) -> {
                    moduleHost.clear();
                    moduleServices.clear();
                    moduleHost.registerRequiredModule(new TianshuManagedModule() {
                        @Override
                        public String moduleId() {
                            return "module.required.failure";
                        }

                        @Override
                        public void prepare(ModuleRuntimeContext context) {
                            throw new IllegalStateException("required prepare failed");
                        }
                    }, capability);
                }
        );

        CoreRuntimeStatus status = core.startRuntimeSession().get(5, TimeUnit.SECONDS);

        assertEquals(CoreLifecyclePhase.FAILED, status.corePhase());
        assertEquals(1, status.modules().size());
        assertEquals(ModuleLifecycleState.FAILED, status.modules().iterator().next().state());
        assertEquals("required prepare failed", status.modules().iterator().next().failureReason());
        RuntimeCapabilityStatus capabilityStatus = core.capabilityStatus(capability);
        assertTrue(capabilityStatus.failed());
        assertTrue(capabilityStatus.failureReason().contains("PREPARE"));

        core.destroy().get(5, TimeUnit.SECONDS);
    }

    private static final class RecordingModule implements TianshuManagedModule {
        private final int id;
        private final List<String> calls;
        private final List<String> callbackThreads;

        private RecordingModule(int id, List<String> calls, List<String> callbackThreads) {
            this.id = id;
            this.calls = calls;
            this.callbackThreads = callbackThreads;
        }

        @Override
        public String moduleId() {
            return "module.recording." + id;
        }

        @Override
        public void register(ModuleRegistrationContext context) {
            record("register");
        }

        @Override
        public void prepare(ModuleRuntimeContext context) {
            record("prepare");
        }

        @Override
        public void start(ModuleRuntimeContext context) {
            record("start");
        }

        @Override
        public void stop() {
            record("stop");
        }

        @Override
        public void destroy() {
            record("destroy");
        }

        private void record(String phase) {
            calls.add(id + ":" + phase);
            callbackThreads.add(Thread.currentThread().getName());
        }
    }

    private static class SimpleRecordingModule implements TianshuManagedModule {
        private final int id;
        private final List<String> calls;

        private SimpleRecordingModule(int id, List<String> calls) {
            this.id = id;
            this.calls = calls;
        }

        @Override
        public String moduleId() {
            return "module.simple." + id;
        }

        @Override
        public void register(ModuleRegistrationContext context) {
            record("register");
        }

        @Override
        public void prepare(ModuleRuntimeContext context) {
            record("prepare");
        }

        @Override
        public void start(ModuleRuntimeContext context) {
            record("start");
        }

        @Override
        public void stop() {
            record("stop");
        }

        @Override
        public void destroy() {
            record("destroy");
        }

        protected final void record(String phase) {
            calls.add(id + ":" + phase);
        }
    }

    private static final class BlockingRecordingModule extends SimpleRecordingModule {
        private final CountDownLatch prepareEntered;
        private final CountDownLatch releasePrepare;

        private BlockingRecordingModule(
                int id,
                List<String> calls,
                CountDownLatch prepareEntered,
                CountDownLatch releasePrepare
        ) {
            super(id, calls);
            this.prepareEntered = prepareEntered;
            this.releasePrepare = releasePrepare;
        }

        @Override
        public void prepare(ModuleRuntimeContext context) {
            record("prepare");
            prepareEntered.countDown();
            try {
                if (!releasePrepare.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release prepare");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Prepare interrupted", interrupted);
            }
        }
    }

    private static final class NoopAudioBridge implements IAudioBridge {
        @Override public void ensureHardwareRunning() {}
        @Override public void releaseCaptureHardware() {}
        @Override public void startRecording() {}
        @Override public byte[] stopRecording() { return new byte[0]; }
        @Override public void startStreamRecording(Consumer<byte[]> onAudioChunk) {}
        @Override public void stopStreamRecording() {}
        @Override public void startTtsPlayback(int sampleRate) {}
        @Override public void feedTtsAudio(byte[] audio) {}
        @Override public void finishTtsPlayback() {}
        @Override public void setOnPlaybackFinished(Runnable callback) {}
        @Override public void stopTtsPlayback() {}
        @Override public void playAudio(byte[] audioData, int sampleRate) {}
        @Override public void stopPlayback() {}
        @Override public boolean isRecording() { return false; }
        @Override public boolean isPlaying() { return false; }
        @Override public boolean isStreaming() { return false; }
        @Override public List<String> getAvailableMicNames() { return List.of(); }
        @Override public String getCurrentMicName() { return ""; }
        @Override public void selectMic(String micName) {}
        @Override public void switchToNextMic() {}
        @Override public void shutdown() {}
    }
}
