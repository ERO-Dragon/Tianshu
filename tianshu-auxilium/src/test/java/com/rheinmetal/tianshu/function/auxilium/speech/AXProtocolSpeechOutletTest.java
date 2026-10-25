package com.rheinmetal.tianshu.function.auxilium.speech;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.function.auxilium.producer.AXGameEventTextProvider;
import com.rheinmetal.tianshu.function.auxilium.core.output.AuxiliumOutputMode;
import com.rheinmetal.tianshu.function.auxilium.core.output.AuxiliumOutputSettings;
import com.rheinmetal.tianshu.protocol.voice.VoiceNotificationService;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackPlacement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The voice implementation: resolve keyed lines on the platform side, submit on the existing TTS
 * capability, and never let a missing or switched-off voice channel escape as an exception.
 */
class AXProtocolSpeechOutletTest {

    /** Voice switched on: the mode the dialogue output needs to reach TTS at all. */
    private static final AuxiliumOutputSettings TTS_ON = () -> AuxiliumOutputMode.UI_AND_TTS;

    /** Voice switched off: everything must degrade to a diagnostic. */
    private static final AuxiliumOutputSettings TTS_OFF = () -> AuxiliumOutputMode.UI_ONLY;

    @Test
    void literalLineIsSpokenAsItIs() {
        RecordingVoice voice = new RecordingVoice();
        AXProtocolSpeechOutlet outlet = outlet(voice, TTS_ON, Map.of());

        outlet.speak(AXSpeechLine.literal(
                AXSpeechSource.DELEGATE, AXSpeechPriority.HIGH, "Iron blocks need a stone pickaxe."));

        assertEquals(List.of("Iron blocks need a stone pickaxe."), voice.spoken);
        assertEquals(TtsPlaybackPlacement.INSERT_AFTER_SENTENCE, voice.placements.get(0),
                "a high priority line joins after the current sentence");
        assertEquals(Priority.HIGH, voice.priorities.get(0));
    }

    @Test
    void keyedLineIsResolvedThroughTheLanguageTable() {
        RecordingVoice voice = new RecordingVoice();
        AXProtocolSpeechOutlet outlet = outlet(voice, TTS_ON,
                Map.of("ax.runtime.delegate.ack", "Let me look that up."));

        outlet.speak(AXSpeechLine.keyed(
                AXSpeechSource.DELEGATE, AXSpeechPriority.NORMAL, "ax.runtime.delegate.ack", List.of()));

        assertEquals(List.of("Let me look that up."), voice.spoken);
        assertEquals(TtsPlaybackPlacement.INSERT_AFTER_SESSION, voice.placements.get(0));
        assertEquals(Priority.NORMAL, voice.priorities.get(0));
    }

    @Test
    void unresolvedKeyStaysSilentAndDoesNotThrow() {
        RecordingVoice voice = new RecordingVoice();
        AXProtocolSpeechOutlet outlet = outlet(voice, TTS_ON, Map.of());

        outlet.speak(AXSpeechLine.keyed(
                AXSpeechSource.DELEGATE, AXSpeechPriority.HIGH, "ax.runtime.delegate.unknown", List.of()));

        assertTrue(voice.spoken.isEmpty(), "an unresolved key must never be spoken as text");
    }

    @Test
    void disabledVoiceChannelDoesNotThrow() {
        RecordingVoice voice = new RecordingVoice();
        AXProtocolSpeechOutlet outlet = outlet(voice, TTS_OFF,
                Map.of("ax.runtime.delegate.ack", "Let me look that up."));

        outlet.speak(AXSpeechLine.keyed(
                AXSpeechSource.DELEGATE, AXSpeechPriority.NORMAL, "ax.runtime.delegate.ack", List.of()));
        outlet.speak(AXSpeechLine.literal(AXSpeechSource.EVENT, AXSpeechPriority.HIGH, "You died"));

        assertTrue(voice.spoken.isEmpty(), "TTS switched off is a diagnostic, not a delivery");
    }

    @Test
    void linesAreSubmittedInArrivalOrder() {
        RecordingVoice voice = new RecordingVoice();
        AXProtocolSpeechOutlet outlet = outlet(voice, TTS_ON, Map.of());

        outlet.speak(AXSpeechLine.literal(AXSpeechSource.EVENT, AXSpeechPriority.HIGH, "first"));
        outlet.speak(AXSpeechLine.literal(AXSpeechSource.DELEGATE, AXSpeechPriority.HIGH, "second"));
        outlet.speak(AXSpeechLine.literal(AXSpeechSource.EVENT, AXSpeechPriority.HIGH, "third"));

        assertEquals(List.of("first", "second", "third"), voice.spoken, "arrival order is the queue order");
    }

    @Test
    void aBrokenVoiceChannelIsReportedInsteadOfThrown() {
        VoiceNotificationService broken = new VoiceNotificationService(null) {
            @Override
            public void speakQueued(String text, TtsPlaybackPlacement placement, Priority priority) {
                throw new IllegalStateException("no audio device");
            }
        };
        AXProtocolSpeechOutlet outlet = new AXProtocolSpeechOutlet(
                broken, provider(Map.of()), TTS_ON, LogSink.NOOP);

        outlet.speak(AXSpeechLine.literal(AXSpeechSource.EVENT, AXSpeechPriority.HIGH, "You died"));
    }

    // ---------------------------------------------------------------- fixtures

    private static AXProtocolSpeechOutlet outlet(
            VoiceNotificationService voice,
            AuxiliumOutputSettings settings,
            Map<String, String> table) {
        return new AXProtocolSpeechOutlet(voice, provider(table), settings, LogSink.NOOP);
    }

    private static AXGameEventTextProvider provider(Map<String, String> table) {
        return new AXGameEventTextProvider() {
            @Override
            public boolean exists(String key) {
                return key != null && table.containsKey(key);
            }

            @Override
            public String text(String key, Object... args) {
                return exists(key) ? table.get(key) : "";
            }
        };
    }

    private static final class RecordingVoice extends VoiceNotificationService {
        private final List<String> spoken = new ArrayList<>();
        private final List<TtsPlaybackPlacement> placements = new ArrayList<>();
        private final List<Priority> priorities = new ArrayList<>();

        private RecordingVoice() {
            super(null);
        }

        @Override
        public void speakQueued(String text, TtsPlaybackPlacement placement, Priority priority) {
            spoken.add(text);
            placements.add(placement);
            priorities.add(priority);
        }
    }
}
