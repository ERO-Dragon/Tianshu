package com.rheinmetal.tianshu.function.auxilium.bridge;

import com.rheinmetal.tianshu.api.assembly.InstalledMods;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.function.auxilium.speech.AXSpeechSink;
import com.rheinmetal.tianshu.function.auxilium.speech.AXSpeechUtterance;
import com.rheinmetal.tianshu.function.echo.contract.TtsControlPayload;
import com.rheinmetal.tianshu.function.echo.contract.TtsSpeakPayload;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one decision that makes the speech module an integration rather than a dependency.
 *
 * <p>Every other test in this module wires {@link EchoSpeechBridge} in directly, which answers "does the
 * bridge work" but never "is the bridge chosen only when the speech module is installed". That second
 * question is the one that decides whether a session without that mod is silent or crashes, and it was
 * answered nowhere: this class is the only caller of {@link AXSpeechSinks#forRuntime}.</p>
 *
 * <p>Both readings matter. Absent must not throw and must not speak; present must actually reach the
 * protocol — a guard that always returns {@link AXSpeechSink#NONE} would satisfy every "does not crash"
 * assertion while quietly disabling voice output.</p>
 */
class AXSpeechSinkSelectionTest {

    private static final AXSpeechUtterance.VoiceProfile DEFAULT_VOICE =
            AXSpeechUtterance.VoiceProfile.defaults();

    @BeforeEach
    @AfterEach
    void resetPorts() {
        TianshuRuntimePorts.clear();
    }

    @Test
    void aMissingRuntimeIsSilentInsteadOfThrowing() {
        assertSame(AXSpeechSink.NONE, AXSpeechSinks.forRuntime(null));
    }

    @Test
    void noRegisteredPortMeansTheSpeechModuleCountsAsAbsent() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);

        AXSpeechSink sink = AXSpeechSinks.forRuntime(runtime);

        assertSame(AXSpeechSink.NONE, sink,
                "the unknown case must fail closed: a partner nobody reports is not on the classpath");
    }

    @Test
    void anExplicitlyAbsentSpeechModuleIsSilent() {
        TianshuRuntimePorts.register(InstalledMods.class, modId -> false);
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);

        assertSame(AXSpeechSink.NONE, AXSpeechSinks.forRuntime(runtime));
    }

    @Test
    void anotherModuleBeingInstalledDoesNotEnableTheBridge() {
        TianshuRuntimePorts.register(InstalledMods.class, ProtocolModuleIds.LLM::equals);
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);

        assertSame(AXSpeechSink.NONE, AXSpeechSinks.forRuntime(runtime),
                "the guard asks about the speech module specifically, not about any optional partner");
    }

    @Test
    void anInstalledSpeechModuleIsReachedThroughTheBridge() {
        TianshuRuntimePorts.register(InstalledMods.class, ProtocolModuleIds.TTS::equals);
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        List<String> spoken = new CopyOnWriteArrayList<>();
        registerSpeechSink(runtime, spoken);

        AXSpeechSink sink = AXSpeechSinks.forRuntime(runtime);
        sink.speak(AXSpeechUtterance.command(
                "The furnace is out of fuel.",
                AXSpeechUtterance.Placement.AFTER_SESSION,
                Priority.NORMAL,
                DEFAULT_VOICE));
        await(() -> spoken.size() == 1);

        assertNotSame(AXSpeechSink.NONE, sink, "an installed speech module must not be routed to silence");
        assertEquals(List.of("The furnace is out of fuel."), List.copyOf(spoken));
    }

    @Test
    void theSilentSinkSwallowsUtterancesWithoutTouchingTheProtocol() {
        TianshuRuntimePorts.register(InstalledMods.class, modId -> false);
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        List<String> spoken = new CopyOnWriteArrayList<>();
        registerSpeechSink(runtime, spoken);

        AXSpeechSink sink = AXSpeechSinks.forRuntime(runtime);
        sink.speak(AXSpeechUtterance.command("unheard", AXSpeechUtterance.Placement.AFTER_SESSION,
                Priority.NORMAL, DEFAULT_VOICE));
        sink.stopSource("test");
        await(() -> false, 100L);

        assertTrue(spoken.isEmpty(), "silence must be the absence of a message, not a dropped one");
    }

    @Test
    void stopSourceIsSafeOnBothReadings() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        registerSpeechSink(runtime, new CopyOnWriteArrayList<>());

        TianshuRuntimePorts.register(InstalledMods.class, modId -> false);
        AXSpeechSink absent = AXSpeechSinks.forRuntime(runtime);
        TianshuRuntimePorts.register(InstalledMods.class, ProtocolModuleIds.TTS::equals);
        AXSpeechSink present = AXSpeechSinks.forRuntime(runtime);

        assertDoesNotThrow(() -> {
            absent.stopSource("absence");
            present.stopSource("presence");
        }, "interrupting an absent speech channel must not escape as an exception");
    }

    // ---------------------------------------------------------------- fixtures

    private static void registerSpeechSink(ProtocolRuntime runtime, List<String> spoken) {
        AdapterDefaults defaults = AdapterDefaults.standard().withConcurrency(1, 64);
        runtime.registerModule(new ModuleDescriptor(
                ProtocolModuleIds.TTS,
                List.of(
                        new CapabilityDescriptor(
                                ProtocolCapabilities.TTS_SPEAK,
                                PayloadType.TTS_TEXT,
                                TtsSpeakPayload.class,
                                BrokerType.BOUNDED_QUEUE,
                                EnumSet.of(PacketType.COMMAND, PacketType.STREAM_CHUNK, PacketType.STREAM_END),
                                Priority.LOW,
                                CompletionPolicy.MANUAL_COMPLETE
                        ),
                        new CapabilityDescriptor(
                                ProtocolCapabilities.TTS_CONTROL,
                                PayloadType.CUSTOM,
                                TtsControlPayload.class,
                                BrokerType.BOUNDED_QUEUE,
                                EnumSet.of(PacketType.COMMAND),
                                Priority.LOW,
                                CompletionPolicy.MANUAL_COMPLETE
                        )
                ),
                defaults.threadPolicy(),
                defaults.cancellationScope(),
                defaults.failurePolicy(),
                defaults.deliveryPolicy(),
                defaults.cancellable(),
                defaults.supportsStreaming(),
                defaults.maxConcurrency(),
                defaults.queueCapacity()
        ), (envelope, context) -> {
            if (envelope.payload() instanceof TtsSpeakPayload payload && !payload.text().isBlank()) {
                spoken.add(payload.text());
            }
            context.complete(envelope.envelopeId());
        });
    }

    private static void await(BooleanSupplier condition) {
        await(condition, 2000L);
    }

    private static void await(BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
