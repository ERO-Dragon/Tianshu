package com.rheinmetal.tianshu.dubhe.speech;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.dubhe.core.output.DubheOutputMode;
import com.rheinmetal.tianshu.dubhe.core.output.DubheOutputSettings;
import com.rheinmetal.tianshu.dubhe.producer.DubheGameEventTextProvider;
import com.rheinmetal.tianshu.protocol.Priority;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The voice outlet: resolve keyed lines on the platform side, hand the sentence to the speech sink, and
 * never let a missing or switched-off voice channel escape as an exception.
 *
 * <p>The sink is the seam that keeps this module independent of the speech module: these tests record what
 * the outlet submits, and the bridge is free to translate it into whatever the speech module accepts.</p>
 */
class DubheProtocolSpeechOutletTest {

    /** Voice switched on: the mode the dialogue output needs to reach speech at all. */
    private static final DubheOutputSettings TTS_ON = () -> DubheOutputMode.UI_AND_TTS;

    /** Voice switched off: everything must degrade to a diagnostic. */
    private static final DubheOutputSettings TTS_OFF = () -> DubheOutputMode.UI_ONLY;

    @Test
    void literalLineIsSpokenAsItIs() {
        RecordingVoice voice = new RecordingVoice();
        DubheProtocolSpeechOutlet outlet = outlet(voice, TTS_ON, Map.of());

        outlet.speak(DubheSpeechLine.literal(
                DubheSpeechSource.DELEGATE, DubheSpeechPriority.HIGH, "Iron blocks need a stone pickaxe."));

        assertEquals(List.of("Iron blocks need a stone pickaxe."), voice.spoken);
        assertEquals(DubheSpeechUtterance.Placement.AFTER_SENTENCE, voice.placements.get(0),
                "a high priority line joins after the current sentence");
        assertEquals(Priority.HIGH, voice.priorities.get(0));
    }

    @Test
    void keyedLineIsResolvedThroughTheLanguageTable() {
        RecordingVoice voice = new RecordingVoice();
        DubheProtocolSpeechOutlet outlet = outlet(voice, TTS_ON,
                Map.of("ax.runtime.delegate.ack", "Let me look that up."));

        outlet.speak(DubheSpeechLine.keyed(
                DubheSpeechSource.DELEGATE, DubheSpeechPriority.NORMAL, "ax.runtime.delegate.ack", List.of()));

        assertEquals(List.of("Let me look that up."), voice.spoken);
        assertEquals(DubheSpeechUtterance.Placement.AFTER_SESSION, voice.placements.get(0));
        assertEquals(Priority.NORMAL, voice.priorities.get(0));
    }

    @Test
    void unresolvedKeyStaysSilentAndDoesNotThrow() {
        RecordingVoice voice = new RecordingVoice();
        DubheProtocolSpeechOutlet outlet = outlet(voice, TTS_ON, Map.of());

        outlet.speak(DubheSpeechLine.keyed(
                DubheSpeechSource.DELEGATE, DubheSpeechPriority.HIGH, "ax.runtime.delegate.unknown", List.of()));

        assertTrue(voice.spoken.isEmpty(), "an unresolved key must never be spoken as text");
    }

    @Test
    void disabledVoiceChannelDoesNotThrow() {
        RecordingVoice voice = new RecordingVoice();
        DubheProtocolSpeechOutlet outlet = outlet(voice, TTS_OFF,
                Map.of("ax.runtime.delegate.ack", "Let me look that up."));

        outlet.speak(DubheSpeechLine.keyed(
                DubheSpeechSource.DELEGATE, DubheSpeechPriority.NORMAL, "ax.runtime.delegate.ack", List.of()));
        outlet.speak(DubheSpeechLine.literal(DubheSpeechSource.EVENT, DubheSpeechPriority.HIGH, "You died"));

        assertTrue(voice.spoken.isEmpty(), "voice switched off is a diagnostic, not a delivery");
    }

    @Test
    void linesAreSubmittedInArrivalOrder() {
        RecordingVoice voice = new RecordingVoice();
        DubheProtocolSpeechOutlet outlet = outlet(voice, TTS_ON, Map.of());

        outlet.speak(DubheSpeechLine.literal(DubheSpeechSource.EVENT, DubheSpeechPriority.HIGH, "first"));
        outlet.speak(DubheSpeechLine.literal(DubheSpeechSource.DELEGATE, DubheSpeechPriority.HIGH, "second"));
        outlet.speak(DubheSpeechLine.literal(DubheSpeechSource.EVENT, DubheSpeechPriority.HIGH, "third"));

        assertEquals(List.of("first", "second", "third"), voice.spoken, "arrival order is the queue order");
    }

    @Test
    void aBrokenVoiceChannelIsReportedInsteadOfThrown() {
        DubheSpeechSink broken = new DubheSpeechSink() {
            @Override
            public void speak(DubheSpeechUtterance utterance) {
                throw new IllegalStateException("no audio device");
            }

            @Override
            public void stopSource(String reasonCode) {
            }
        };
        DubheProtocolSpeechOutlet outlet = new DubheProtocolSpeechOutlet(
                broken, provider(Map.of()), TTS_ON, LogSink.NOOP);

        outlet.speak(DubheSpeechLine.literal(DubheSpeechSource.EVENT, DubheSpeechPriority.HIGH, "You died"));
    }

    // ---------------------------------------------------------------- fixtures

    private static DubheProtocolSpeechOutlet outlet(
            DubheSpeechSink voice,
            DubheOutputSettings settings,
            Map<String, String> table) {
        return new DubheProtocolSpeechOutlet(voice, provider(table), settings, LogSink.NOOP);
    }

    private static DubheGameEventTextProvider provider(Map<String, String> table) {
        return new DubheGameEventTextProvider() {
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

    private static final class RecordingVoice implements DubheSpeechSink {
        private final List<String> spoken = new ArrayList<>();
        private final List<DubheSpeechUtterance.Placement> placements = new ArrayList<>();
        private final List<Priority> priorities = new ArrayList<>();

        @Override
        public void speak(DubheSpeechUtterance utterance) {
            spoken.add(utterance.text());
            placements.add(utterance.placement());
            priorities.add(utterance.priority());
        }

        @Override
        public void stopSource(String reasonCode) {
        }
    }
}
