package com.rheinmetal.tianshu.function.echo;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.runtime.ModuleRuntimeState;
import com.rheinmetal.tianshu.function.echo.contract.TtsAudioAckPayload;
import com.rheinmetal.tianshu.function.echo.contract.TtsAudioPayload;
import com.rheinmetal.tianshu.function.echo.contract.TtsControlPayload;
import com.rheinmetal.tianshu.function.echo.contract.TtsPlaybackPlacement;
import com.rheinmetal.tianshu.function.echo.contract.TtsRequestStatus;
import com.rheinmetal.tianshu.function.echo.contract.TtsRequestStatusPayload;
import com.rheinmetal.tianshu.function.echo.contract.TtsSpeakPayload;
import com.rheinmetal.tianshu.function.echo.contract.TtsSynthesisRequestPayload;
import com.rheinmetal.tianshu.function.echo.contract.TtsTextInputMode;
import com.rheinmetal.tianshu.function.echo.contract.TtsVoiceOptions;
import com.rheinmetal.tianshu.function.echo.protocol.TtsCapabilities;
import com.rheinmetal.tianshu.function.echo.protocol.TtsTopics;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.EnvelopeTransition;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.trigger.TriggerRegistry;
import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Black-box protocol tests for the speech-output module, written from a third-party caller's seat.
 *
 * <p>Every case builds one envelope as {@code module.example} - a mod that is not the speech module - hands it to the
 * real {@link ProtocolRuntime}, and then only reads what the protocol itself exposes: the envelope
 * lifecycle ({@code statusOf} / {@code transitionsOf}), the {@code TTS.REQUEST_STATUS} topic the caller
 * subscribed to, and the {@code TTS_AUDIO} responses addressed to the caller's own envelope. No private
 * field of {@link TtsModule} is inspected, and no service is called behind the protocol to force an
 * outcome.</p>
 *
 * <p><b>Reality boundary.</b> Real: the protocol runtime with its brokers, executors and lifecycle store;
 * the real {@link TtsModule} with its four capability handlers; the real synthesis scheduling and model
 * lifecycle coordination. Fake: the game host ({@link FakeGameEnvironment}), the configuration
 * ({@link TestLlmSupport.FakeConfig}) and the audio device ({@link NoopAudioBridge}, a headless bridge that
 * produces and consumes nothing). <b>No speech model is installed</b>, so the synthesis engine can never
 * initialize: every path that needs audio must therefore end in a deterministic failure for the caller,
 * and the interesting question is never "was audio produced" but "did the caller get a terminal,
 * diagnosable answer". The automatic model load is deferred past the test
 * ({@link #DEFERRED_AUTO_LOAD_MILLIS}) so that no unsolicited load races the calls.</p>
 *
 * <p><b>Contract this suite pins.</b> Every terminal outcome below was once a defect this suite found; the
 * fixes are in the module, and the assertions stay unweakened so the same drift fails here again.</p>
 * <ol>
 *   <li><b>One reason-code namespace.</b> Every structured failure is {@code TTS_} plus a
 *       {@code TtsFailureCode}, so the input-mode branches fail as {@code TTS_INVALID_REQUEST} instead of
 *       the previous un-namespaced {@code INVALID_TTS_INPUT_MODE}.</li>
 *   <li><b>The voice failure code is the real one.</b> An unknown voice id reaches the caller as
 *       {@code TTS_VOICE_CLONE_UNAVAILABLE}; the obsolete {@code TTS_VOICE_UNAVAILABLE} fallback was
 *       removed together with the parameter that carried it.</li>
 *   <li><b>{@code UNLOAD_VOICE} of a never-registered id fails</b> with {@code TTS_REQUEST_NOT_FOUND}, so
 *       "unloaded" and "no such voice" are distinguishable.</li>
 *   <li><b>A wrong payload class never reaches the module.</b> The registry rejects it before dispatch and
 *       the envelope ends as {@code DEAD_LETTERED/PAYLOAD_CLASS_MISMATCH}; the handler's
 *       {@code INVALID_PAYLOAD} branch only covers a payload the capability already accepted.</li>
 * </ol>
 */
class TtsProtocolBlackBoxTest {

    /** The third-party mod calling into the speech module; every envelope in this suite comes from it. */
    private static final String CALLER = "module.example";

    /**
     * The auto-load is pushed far beyond any test run on purpose: the scenario under test is "the feature
     * is installed but its model is not", so nothing may load a model in the background half way through.
     */
    private static final long DEFERRED_AUTO_LOAD_MILLIS = 3_600_000L;

    private static final long TIMEOUT_MILLIS = 5_000L;
    private static final long QUIET_WINDOW_MILLIS = 300L;

    private static final PayloadType TEXT_PAYLOAD_TYPE = PayloadType.of("TTS_TEXT");
    private static final PayloadType AUDIO_ACK_PAYLOAD_TYPE = PayloadType.of("TTS_AUDIO_ACK");
    private static final PayloadType AUDIO_PAYLOAD_TYPE = PayloadType.of("TTS_AUDIO");
    private static final PayloadType REQUEST_STATUS_PAYLOAD_TYPE = PayloadType.of("TTS_REQUEST_STATUS");

    private static final Set<EnvelopeStatus> TERMINAL_STATUSES = Set.of(
            EnvelopeStatus.COMPLETED,
            EnvelopeStatus.CANCELLED,
            EnvelopeStatus.EXPIRED,
            EnvelopeStatus.FAILED,
            EnvelopeStatus.REJECTED,
            EnvelopeStatus.DEAD_LETTERED
    );

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------ scenarios

    @Test
    void aSpeakCommandWithDocumentTextIsAdmittedAndNeverDeliversAudio() {
        try (Harness harness = open(true)) {
            TianshuEnvelope envelope = harness.speakCommand(documentText("Hello there. The furnace is out of fuel."));

            assertStatus(harness.protocol, envelope, EnvelopeStatus.COMPLETED);

            TtsRequestStatusPayload failed = awaitRequestStatus(
                    harness.requestStatuses, TtsRequestStatus.FAILED, "the missing model");
            assertEquals(CALLER, failed.sourceId(),
                    () -> "the failure must be reported for the calling mod, got " + failed);
            assertEquals("SYNTHESIS_ENGINE_UNAVAILABLE", failed.failureCode(),
                    () -> "the status must say why the text will never be spoken, got " + failed);

            assertNoAudioResponse(harness, envelope.envelopeId());
        }
    }

    @Test
    void aSpeakCommandInSentenceStreamModeIsRejectedWithTheTtsInputModeCode() {
        try (Harness harness = open(true)) {
            TianshuEnvelope envelope = harness.speakCommand(sentenceStreamText("Hello there."));

            assertEquals(EnvelopeStatus.FAILED, awaitStatus(harness.protocol, envelope.envelopeId()),
                    () -> "a command packet must refuse a streaming input mode: " + trail(harness.protocol, envelope.envelopeId()));
            assertEquals("TTS_INVALID_REQUEST", failureReason(harness.protocol, envelope.envelopeId()),
                    () -> "the caller needs the namespaced reason code: " + trail(harness.protocol, envelope.envelopeId()));
        }
    }

    @Test
    void aStreamChunkWithDocumentTextIsRejectedWithTheTtsInputModeCode() {
        try (Harness harness = open(true)) {
            TianshuEnvelope envelope = harness.speakChunk(documentText("Hello there."));

            assertEquals(EnvelopeStatus.FAILED, awaitStatus(harness.protocol, envelope.envelopeId()),
                    () -> "a stream packet must refuse document input mode: " + trail(harness.protocol, envelope.envelopeId()));
            assertEquals("TTS_INVALID_REQUEST", failureReason(harness.protocol, envelope.envelopeId()),
                    () -> "the caller needs the namespaced reason code: " + trail(harness.protocol, envelope.envelopeId()));
        }
    }

    @Test
    void aSentenceStreamIsAdmittedPacketByPacketAndTheSameSessionCanStartAgain() {
        try (Harness harness = open(true)) {
            TianshuEnvelope first = harness.speakChunk(streamText("Hello there.", 1, 42L));
            TianshuEnvelope second = harness.speakChunk(streamText("This is a streamed sentence.", 1, 42L));
            TianshuEnvelope end = harness.speakEnd(streamText("", 1, 42L));

            for (TianshuEnvelope packet : List.of(first, second, end)) {
                assertStatus(harness.protocol, packet, EnvelopeStatus.COMPLETED);
            }

            // The stream is over, so the session barrier must be gone: a new stream on the same
            // sessionId/turnId is re-admitted instead of being refused or left in RUNNING.
            TianshuEnvelope reopened = harness.speakChunk(streamText("Again.", 1, 42L));
            assertStatus(harness.protocol, reopened, EnvelopeStatus.COMPLETED);
        }
    }

    @Test
    void anUnregisteredVoiceIdIsRejectedAsAPermanentFailure() {
        try (Harness harness = open(true)) {
            TtsSpeakPayload payload = new TtsSpeakPayload(
                    "Hello there.",
                    0,
                    0L,
                    TtsPlaybackPlacement.QUEUE_AFTER_SESSION,
                    TtsTextInputMode.DOCUMENT,
                    new TtsVoiceOptions("nobody:voice", null, null)
            );
            TianshuEnvelope envelope = harness.speakCommand(payload);

            assertEquals(EnvelopeStatus.FAILED, awaitStatus(harness.protocol, envelope.envelopeId()),
                    () -> "an unknown voice id must not be accepted silently: " + trail(harness.protocol, envelope.envelopeId()));
            String reason = failureReason(harness.protocol, envelope.envelopeId());
            assertEquals("TTS_VOICE_CLONE_UNAVAILABLE", reason,
                    () -> "the caller must be able to tell 'voice not available' apart: "
                            + trail(harness.protocol, envelope.envelopeId()));
        }
    }

    @Test
    void blankTextNeitherThrowsNorLeavesTheCallerWaiting() {
        try (Harness harness = open(true)) {
            TianshuEnvelope empty = assertDoesNotThrow(() -> harness.speakCommand(documentText("")),
                    "submitting an empty text must not throw in the caller's thread");
            TianshuEnvelope whitespace = assertDoesNotThrow(() -> harness.speakCommand(documentText("   ")),
                    "submitting a whitespace-only text must not throw in the caller's thread");

            for (TianshuEnvelope envelope : List.of(empty, whitespace)) {
                EnvelopeStatus status = awaitStatus(harness.protocol, envelope.envelopeId());
                assertTrue(TERMINAL_STATUSES.contains(status),
                        () -> "blank text must still reach a terminal state, saw " + status + " / "
                                + trail(harness.protocol, envelope.envelopeId()));
                assertEquals(EnvelopeStatus.FAILED, status,
                        () -> "a document with nothing to say must be a structured failure, saw " + status
                                + " / " + trail(harness.protocol, envelope.envelopeId()));
                assertEquals("TTS_EMPTY_TEXT", failureReason(harness.protocol, envelope.envelopeId()),
                        () -> "the caller needs TTS_EMPTY_TEXT, got "
                                + trail(harness.protocol, envelope.envelopeId()));
            }
        }
    }

    @Test
    void aSynthesisRequestEndsInAFailureInsteadOfHangingAndDeliversNoAudio() {
        try (Harness harness = open(true)) {
            TianshuEnvelope envelope = harness.synthesize(
                    new TtsSynthesisRequestPayload("req-black-box-1", "Hello there.", 5_000L, TtsVoiceOptions.defaults()));

            EnvelopeStatus status = awaitStatus(harness.protocol, envelope.envelopeId());
            assertEquals(EnvelopeStatus.FAILED, status,
                    () -> "a synthesis request without a model must fail the caller instead of staying "
                            + status + " / " + trail(harness.protocol, envelope.envelopeId()));
            assertEquals("TTS_SYNTHESIS_ENGINE_UNAVAILABLE", failureReason(harness.protocol, envelope.envelopeId()),
                    () -> "the failure must name the missing engine: " + trail(harness.protocol, envelope.envelopeId()));

            assertNoAudioResponse(harness, envelope.envelopeId());
        }
    }

    @Test
    void aSynthesisRequestWithABlankRequestIdAlsoEndsInAFailure() {
        try (Harness harness = open(true)) {
            TianshuEnvelope envelope = harness.synthesize(
                    new TtsSynthesisRequestPayload("   ", "Hello there.", 5_000L, TtsVoiceOptions.defaults()));

            EnvelopeStatus status = awaitStatus(harness.protocol, envelope.envelopeId());
            assertEquals(EnvelopeStatus.FAILED, status,
                    () -> "a blank requestId must not crash or wedge the request, observed " + status + " / "
                            + trail(harness.protocol, envelope.envelopeId()));
            assertEquals("TTS_SYNTHESIS_ENGINE_UNAVAILABLE", failureReason(harness.protocol, envelope.envelopeId()),
                    () -> "the blank id must fail for the real reason, not because of the id: "
                            + trail(harness.protocol, envelope.envelopeId()));

            assertNoAudioResponse(harness, envelope.envelopeId());
        }
    }

    @Test
    void acknowledgingAnUnknownRequestFailsWithAnAudioAckReason() {
        try (Harness harness = open(true)) {
            TianshuEnvelope envelope = harness.acknowledgeAudio("request-that-never-existed");

            assertEquals(EnvelopeStatus.FAILED, awaitStatus(harness.protocol, envelope.envelopeId()),
                    () -> "acking an unknown request must fail, not succeed: " + trail(harness.protocol, envelope.envelopeId()));
            String reason = failureReason(harness.protocol, envelope.envelopeId());
            assertTrue(reason.contains("TTS_AUDIO_ACK_FAILED") || reason.contains("TTS_REQUEST_NOT_FOUND"),
                    () -> "the caller needs a recognisable ack failure, got " + reason
                            + " / " + trail(harness.protocol, envelope.envelopeId()));
            assertEquals("TTS_REQUEST_NOT_FOUND", reason,
                    () -> "the caller must see that the request does not exist, not a generic ack error: "
                            + trail(harness.protocol, envelope.envelopeId()));
        }
    }

    @Test
    void stoppingEverythingWithoutATargetCompletes() {
        try (Harness harness = open(true)) {
            TianshuEnvelope envelope = harness.control(
                    new TtsControlPayload(TtsControlPayload.Action.STOP, "", "", "stop everything"));

            assertEquals(EnvelopeStatus.COMPLETED, awaitStatus(harness.protocol, envelope.envelopeId()),
                    () -> "stopping nothing is still a successful stop: " + trail(harness.protocol, envelope.envelopeId()));
        }
    }

    @Test
    void stoppingABlankSourceIsStructuredFailureOrSuccessButNeverAnEscape() {
        try (Harness harness = open(true)) {
            TianshuEnvelope envelope = assertDoesNotThrow(
                    () -> harness.control(new TtsControlPayload(TtsControlPayload.Action.STOP_SOURCE, "", "", "")),
                    "the protocol call itself must not throw in the caller's thread");

            EnvelopeStatus status = awaitStatus(harness.protocol, envelope.envelopeId());
            assertTrue(TERMINAL_STATUSES.contains(status),
                    () -> "a blank targetSource must not leave the caller waiting, saw " + status + " / "
                            + trail(harness.protocol, envelope.envelopeId()));
            assertTrue(status == EnvelopeStatus.COMPLETED || status == EnvelopeStatus.FAILED,
                    () -> "expected a structured success or failure, saw " + status + " / "
                            + trail(harness.protocol, envelope.envelopeId()));
            assertTrue(status == EnvelopeStatus.COMPLETED
                            || (status == EnvelopeStatus.FAILED && !failureReason(harness.protocol, envelope.envelopeId()).isEmpty()),
                    () -> "a refusal must still carry a reason code, saw " + status + " / "
                            + trail(harness.protocol, envelope.envelopeId()));
            assertNotEquals("HANDLER_EXCEPTION", failureReason(harness.protocol, envelope.envelopeId()),
                    () -> "the blank source must be handled, not thrown out of the handler: "
                            + trail(harness.protocol, envelope.envelopeId()));
        }
    }

    @Test
    void unloadingAVoiceThatWasNeverRegisteredIsReportedAsAFailure() {
        try (Harness harness = open(true)) {
            TianshuEnvelope envelope = harness.control(new TtsControlPayload(
                    TtsControlPayload.Action.UNLOAD_VOICE, "never-registered-voice", "", "", ""));

            assertEquals(EnvelopeStatus.FAILED, awaitStatus(harness.protocol, envelope.envelopeId()),
                    () -> "unloading an unknown voice must not claim success: "
                            + trail(harness.protocol, envelope.envelopeId()));
            assertEquals("TTS_REQUEST_NOT_FOUND", failureReason(harness.protocol, envelope.envelopeId()),
                    () -> "the caller must be able to tell 'not registered' apart: "
                            + trail(harness.protocol, envelope.envelopeId()));
        }
    }

    @Test
    void importingAVoiceWithAnEscapingIdFailsAndWritesNothingOutsideTheVoiceLibrary() throws Exception {
        try (Harness harness = open(true)) {
            byte[] audio = new byte[] {1, 2, 3, 4};
            TianshuEnvelope escaping = harness.control(new TtsControlPayload(
                    TtsControlPayload.Action.IMPORT_VOICE, "../escape", audio, ""));

            assertEquals(EnvelopeStatus.FAILED, awaitStatus(harness.protocol, escaping.envelopeId()),
                    () -> "a path-escaping voice id must be refused: " + trail(harness.protocol, escaping.envelopeId()));

            Path voiceRoot = tempDir.resolve("tts").resolve("voice").toAbsolutePath().normalize();
            List<Path> outsideVoiceRoot = regularFilesOutside(voiceRoot);
            assertTrue(outsideVoiceRoot.isEmpty(),
                    () -> "the import created files outside the voice library: " + outsideVoiceRoot);
            assertFalse(Files.exists(tempDir.getParent().resolve("escape.wav")),
                    "the import escaped one directory up");
            assertFalse(Files.exists(tempDir.resolve("escape.wav")),
                    "the import wrote a sanitised-but-wrong file in the data root");
        }
    }

    @Test
    void stoppingWithABlankReasonAndNoTargetCompletes() {
        try (Harness harness = open(true)) {
            TianshuEnvelope envelope = assertDoesNotThrow(
                    () -> harness.control(new TtsControlPayload(TtsControlPayload.Action.STOP, "", "", "")),
                    "a blank reason must not throw in the caller's thread");

            assertEquals(EnvelopeStatus.COMPLETED, awaitStatus(harness.protocol, envelope.envelopeId()),
                    () -> "a blank reason must fall back to a default, not fail the stop: "
                            + trail(harness.protocol, envelope.envelopeId()));
            assertNotEquals("HANDLER_EXCEPTION", failureReason(harness.protocol, envelope.envelopeId()));
        }
    }

    /**
     * A payload class the capability does not accept never reaches the module: the registry rejects it
     * before dispatch and the envelope is dead-lettered. That is the host's rule for every module, so the
     * caller-visible outcome to pin here is DEAD_LETTERED, not the handler's INVALID_PAYLOAD branch.
     */
    @Test
    void aWrongPayloadTypeIsRejectedByTheRegistryBeforeItReachesTheModule() {
        try (Harness harness = open(true)) {
            TianshuEnvelope envelope = harness.speakWithSynthesisPayload(
                    new TtsSynthesisRequestPayload("req-wrong-payload", "Hello there.", 5_000L, TtsVoiceOptions.defaults()));

            assertEquals(EnvelopeStatus.DEAD_LETTERED, awaitStatus(harness.protocol, envelope.envelopeId()),
                    () -> "a payload of the wrong class must be a caller-visible rejection: "
                            + trail(harness.protocol, envelope.envelopeId()));
            assertEquals("PAYLOAD_CLASS_MISMATCH", terminalReason(harness.protocol, envelope.envelopeId()),
                    () -> "the caller needs PAYLOAD_CLASS_MISMATCH, got "
                            + trail(harness.protocol, envelope.envelopeId()));
        }
    }

    @Test
    void aSpeakCallWhileTtsIsDisabledFailsAsDisabled() {
        try (Harness harness = open(false)) {
            TianshuEnvelope envelope = harness.speakCommand(documentText("Hello there."));

            assertEquals(EnvelopeStatus.FAILED, awaitStatus(harness.protocol, envelope.envelopeId()),
                    () -> "a disabled feature must answer the caller, not drop the envelope: "
                            + trail(harness.protocol, envelope.envelopeId()));
            assertEquals("TTS_DISABLED", failureReason(harness.protocol, envelope.envelopeId()),
                    () -> "the caller needs TTS_DISABLED: " + trail(harness.protocol, envelope.envelopeId()));
        }
    }

    // ------------------------------------------------------------------ the wiring under test

    /**
     * One real runtime plus the real module, plus the caller's own subscription and audio probes.
     *
     * <p>The caller side is deliberately ordinary API: a module descriptor, a topic subscription and a
     * response handler - exactly what another mod would have to write.</p>
     */
    private Harness open(boolean ttsEnabled) {
        FakeGameEnvironment env = new FakeGameEnvironment();
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir)
                .llmEnabled(ttsEnabled)
                .ttsAutoLoadDelayMillis(DEFERRED_AUTO_LOAD_MILLIS);
        ProtocolRuntime protocol = new ProtocolRuntime(Runnable::run);
        ModuleServiceRegistry services = new ModuleServiceRegistry();
        TtsModule module = new TtsModule(new NoopAudioBridge(), protocol, env, config);
        module.register(new ModuleRegistrationContext(protocol, services));
        module.prepare(new ModuleRuntimeContext(protocol, services, new TriggerRegistry(), new ModuleRuntimeState()));
        module.start(null);
        return new Harness(protocol, module);
    }

    private static ModuleDescriptor callerModule(String moduleId) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        return new ModuleDescriptor(
                moduleId,
                List.of(),
                defaults.threadPolicy(),
                defaults.cancellationScope(),
                defaults.failurePolicy(),
                defaults.deliveryPolicy(),
                defaults.cancellable(),
                defaults.supportsStreaming(),
                defaults.maxConcurrency(),
                defaults.queueCapacity()
        );
    }

    private static TtsSpeakPayload documentText(String text) {
        return new TtsSpeakPayload(
                text, 0, 0L, TtsPlaybackPlacement.QUEUE_AFTER_SESSION, TtsTextInputMode.DOCUMENT, TtsVoiceOptions.defaults());
    }

    private static TtsSpeakPayload sentenceStreamText(String text) {
        return new TtsSpeakPayload(
                text, 0, 0L, TtsPlaybackPlacement.QUEUE_AFTER_SESSION, TtsTextInputMode.SENTENCE_STREAM, TtsVoiceOptions.defaults());
    }

    private static TtsSpeakPayload streamText(String text, int turnId, long sessionId) {
        return new TtsSpeakPayload(
                text, turnId, sessionId, TtsPlaybackPlacement.QUEUE_AFTER_SESSION,
                TtsTextInputMode.SENTENCE_STREAM, TtsVoiceOptions.defaults());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static EnvelopeStatus awaitStatus(ProtocolRuntime protocol, String envelopeId) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            EnvelopeStatus status = protocol.lifecycle().statusOf(envelopeId);
            if (TERMINAL_STATUSES.contains(status)) {
                return status;
            }
            sleep(10L);
        }
        return protocol.lifecycle().statusOf(envelopeId);
    }

    private static void assertStatus(ProtocolRuntime protocol, TianshuEnvelope envelope, EnvelopeStatus expected) {
        EnvelopeStatus actual = awaitStatus(protocol, envelope.envelopeId());
        assertEquals(expected, actual,
                () -> "expected " + expected + " but the caller observes " + actual + " / "
                        + trail(protocol, envelope.envelopeId()));
    }

    /** Every transition the caller can see, in order, with the reason code the module chose. */
    private static String trail(ProtocolRuntime protocol, String envelopeId) {
        List<EnvelopeTransition> transitions = protocol.lifecycle().transitionsOf(envelopeId);
        StringBuilder trail = new StringBuilder("transitions=").append(transitions.size()).append(" [");
        for (EnvelopeTransition transition : transitions) {
            trail.append(transition.status())
                    .append('/').append(transition.reasonCode())
                    .append(" \"").append(transition.message()).append("\" ");
        }
        return trail.append(']').toString();
    }

    private static String failureReason(ProtocolRuntime protocol, String envelopeId) {
        List<EnvelopeTransition> failures = protocol.lifecycle().transitionsOf(envelopeId).stream()
                .filter(transition -> transition.status() == EnvelopeStatus.FAILED)
                .toList();
        return failures.isEmpty() ? "" : failures.getLast().reasonCode();
    }

    /** The reason code of whatever ended the envelope, including statuses that are not FAILED. */
    private static String terminalReason(ProtocolRuntime protocol, String envelopeId) {
        List<EnvelopeTransition> transitions = protocol.lifecycle().transitionsOf(envelopeId);
        return transitions.isEmpty() ? "" : transitions.getLast().reasonCode();
    }

    private static TtsRequestStatusPayload awaitRequestStatus(
            List<TtsRequestStatusPayload> statuses,
            TtsRequestStatus expected,
            String what
    ) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            for (TtsRequestStatusPayload status : List.copyOf(statuses)) {
                if (status.status() == expected) {
                    return status;
                }
            }
            sleep(10L);
        }
        return fail("no " + expected + " status was published for " + what + "; observed " + statuses);
    }

    /** Gives an asynchronous delivery a fair chance to arrive before asserting that nothing arrived. */
    private static void awaitQuietWindow() {
        long deadline = System.currentTimeMillis() + QUIET_WINDOW_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            sleep(10L);
        }
    }

    private static void assertNoAudioResponse(Harness harness, String parentEnvelopeId) {
        assertTrue(!harness.protocol.responseHandlers().findResponse(parentEnvelopeId, AUDIO_PAYLOAD_TYPE).isEmpty(),
                "the TTS_AUDIO probe must be armed before submitting, otherwise 'no audio' proves nothing");

        awaitQuietWindow();

        assertTrue(harness.audio.isEmpty(),
                () -> "the caller must not receive TTS_AUDIO without a working model, got " + harness.audio.size()
                        + " audio response(s)");
        boolean responseEnvelope = harness.protocol.lifecycle().allEnvelopes().stream()
                .anyMatch(envelope -> parentEnvelopeId.equals(envelope.parentId())
                        && envelope.header().payloadType() == AUDIO_PAYLOAD_TYPE);
        assertFalse(responseEnvelope, "an audio response envelope was emitted for " + parentEnvelopeId);
    }

    /** Every regular file the module wrote under its data root that is not inside the voice library. */
    private List<Path> regularFilesOutside(Path voiceRoot) throws Exception {
        try (Stream<Path> walk = Files.walk(tempDir)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> path.toAbsolutePath().normalize())
                    .filter(path -> !path.startsWith(voiceRoot))
                    .toList();
        }
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * The caller's protocol identity plus everything it observed. Closing it tears down the real module
     * before the runtime, the same order the kernel uses.
     */
    private static final class Harness implements AutoCloseable {
        private final ProtocolRuntime protocol;
        private final TtsModule module;
        private final List<TtsRequestStatusPayload> requestStatuses = new CopyOnWriteArrayList<>();
        private final List<TtsAudioPayload> audio = new CopyOnWriteArrayList<>();

        private Harness(ProtocolRuntime protocol, TtsModule module) {
            this.protocol = protocol;
            this.module = module;
            subscribeRequestStatus();
        }

        private void subscribeRequestStatus() {
            protocol.subscribeTopic(
                    callerModule("module.example.request-status"),
                    new TopicSubscriptionDescriptor(
                            TtsTopics.TTS_REQUEST_STATUS,
                            REQUEST_STATUS_PAYLOAD_TYPE,
                            TtsRequestStatusPayload.class,
                            BrokerType.BOUNDED_QUEUE,
                            EnumSet.of(PacketType.EVENT),
                            Priority.LOW,
                            CompletionPolicy.AUTO_COMPLETE_ON_RETURN
                    ),
                    (envelope, context) -> {
                        if (envelope.payload() instanceof TtsRequestStatusPayload status) {
                            requestStatuses.add(status);
                        }
                    }
            );
        }

        private TianshuEnvelope speakCommand(TtsSpeakPayload payload) {
            return send(speak(payload, PacketType.COMMAND));
        }

        private TianshuEnvelope speakChunk(TtsSpeakPayload payload) {
            return send(speak(payload, PacketType.STREAM_CHUNK));
        }

        private TianshuEnvelope speakEnd(TtsSpeakPayload payload) {
            return send(speak(payload, PacketType.STREAM_END));
        }

        private TianshuEnvelope synthesize(TtsSynthesisRequestPayload payload) {
            return send(EnvelopeBuilder.requestCapability(
                    CALLER, TtsCapabilities.TTS_SYNTHESIZE, TEXT_PAYLOAD_TYPE, payload));
        }

        private TianshuEnvelope acknowledgeAudio(String requestId) {
            return send(EnvelopeBuilder.commandToCapability(
                    CALLER, TtsCapabilities.TTS_AUDIO_ACK, AUDIO_ACK_PAYLOAD_TYPE, new TtsAudioAckPayload(requestId)));
        }

        private TianshuEnvelope control(TtsControlPayload payload) {
            // TTS_CONTROL declares CRITICAL as its minimum accepted priority; a caller that ignores that is
            // refused by the registry before the handler runs, which is a different test.
            return send(EnvelopeBuilder.commandToCapability(
                    CALLER, TtsCapabilities.TTS_CONTROL, PayloadType.CUSTOM, payload).priority(Priority.CRITICAL));
        }

        private TianshuEnvelope speakWithSynthesisPayload(TtsSynthesisRequestPayload payload) {
            return send(EnvelopeBuilder.commandToCapability(
                    CALLER, TtsCapabilities.TTS_SPEAK, TEXT_PAYLOAD_TYPE, payload));
        }

        private static EnvelopeBuilder speak(TtsSpeakPayload payload, PacketType packetType) {
            return EnvelopeBuilder.commandToCapability(
                    CALLER, TtsCapabilities.TTS_SPEAK, TEXT_PAYLOAD_TYPE, payload).packetType(packetType);
        }

        private TianshuEnvelope send(EnvelopeBuilder builder) {
            TianshuEnvelope envelope = builder.build();
            armAudioProbe(envelope.envelopeId());
            protocol.submit(envelope);
            return envelope;
        }

        /** An ordinary response handler: if the module answered with TTS_AUDIO, this is where it lands. */
        private void armAudioProbe(String requestEnvelopeId) {
            protocol.registerResponseHandler(
                    requestEnvelopeId,
                    callerModule("module.example.audio"),
                    new CapabilityDescriptor(
                            "response:" + requestEnvelopeId + ":TTS_AUDIO",
                            AUDIO_PAYLOAD_TYPE,
                            TtsAudioPayload.class,
                            BrokerType.BOUNDED_QUEUE,
                            EnumSet.of(PacketType.RESPONSE),
                            Priority.LOW,
                            CompletionPolicy.AUTO_COMPLETE_ON_RETURN
                    ),
                    (envelope, context) -> {
                        if (envelope.payload() instanceof TtsAudioPayload payload) {
                            audio.add(payload);
                        }
                    }
            );
        }

        @Override
        public void close() {
            try {
                module.stop();
                module.destroy();
            } finally {
                protocol.close();
            }
        }
    }

    /** Headless audio device: the model is missing, so nothing is ever played. */
    private static final class NoopAudioBridge implements IAudioBridge {
        @Override public void ensureHardwareRunning() { }
        @Override public void releaseCaptureHardware() { }
        @Override public void startRecording() { }
        @Override public byte[] stopRecording() { return new byte[0]; }
        @Override public void startStreamRecording(java.util.function.Consumer<byte[]> onAudioChunk) { }
        @Override public void stopStreamRecording() { }
        @Override public void startTtsPlayback(int sampleRate) { }
        @Override public void feedTtsAudio(byte[] audio) { }
        @Override public void finishTtsPlayback() { }
        @Override public void setOnPlaybackFinished(Runnable callback) { }
        @Override public void stopTtsPlayback() { }
        @Override public void playAudio(byte[] audioData, int sampleRate) { }
        @Override public void stopPlayback() { }
        @Override public boolean isRecording() { return false; }
        @Override public boolean isPlaying() { return false; }
        @Override public boolean isStreaming() { return false; }
        @Override public List<String> getAvailableMicNames() { return List.of(); }
        @Override public String getCurrentMicName() { return ""; }
        @Override public void selectMic(String micName) { }
        @Override public void switchToNextMic() { }
        @Override public void shutdown() { }
    }
}
