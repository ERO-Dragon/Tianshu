package com.rheinmetal.tianshu.function.auxilium.speech;

import com.rheinmetal.tianshu.protocol.Priority;

/**
 * One sentence this module wants spoken, in this module's own vocabulary.
 *
 * <p>Auxilium integrates with the speech module instead of depending on it, so the thing it hands over has
 * to be expressible without that module's types: text, which turn and session it belongs to, how it joins
 * the queue, and the voice parameters to render it with. The bridge translates it; nothing outside the
 * bridge knows a TTS payload exists.</p>
 */
public record AXSpeechUtterance(
        String text,
        int turnId,
        long sessionId,
        Mode mode,
        Placement placement,
        Priority priority,
        VoiceProfile voice
) {

    /** How the receiver should treat this submission. */
    public enum Mode {
        /** A standalone sentence. */
        COMMAND,
        /** A chunk of a stream the receiver reassembles. */
        STREAM_CHUNK,
        /** Closes the stream opened by the preceding chunks. */
        STREAM_END
    }

    /** Where the sentence joins the receiver's queue; none of these interrupts on its own. */
    public enum Placement {
        /** Right after the sentence currently being spoken. */
        AFTER_SENTENCE,
        /** After the session currently being spoken. */
        AFTER_SESSION,
        /** Queue behind everything already accepted in this session. */
        QUEUE_AFTER_SESSION
    }

    /** Voice parameters, all plain values so they survive the trip through the protocol. */
    public record VoiceProfile(double speed, int speakerId, String voiceSample) {

        public static VoiceProfile defaults() {
            return new VoiceProfile(1.0, 0, "");
        }
    }

    public AXSpeechUtterance {
        text = text == null ? "" : text;
        mode = mode == null ? Mode.STREAM_CHUNK : mode;
        placement = placement == null ? Placement.AFTER_SESSION : placement;
        priority = priority == null ? Priority.NORMAL : priority;
        voice = voice == null ? VoiceProfile.defaults() : voice;
    }

    public static AXSpeechUtterance streamChunk(String text, int turnId, long sessionId, VoiceProfile voice) {
        return new AXSpeechUtterance(text, turnId, sessionId, Mode.STREAM_CHUNK, Placement.QUEUE_AFTER_SESSION,
                Priority.NORMAL, voice);
    }

    public static AXSpeechUtterance streamEnd(int turnId, long sessionId, VoiceProfile voice) {
        return new AXSpeechUtterance("", turnId, sessionId, Mode.STREAM_END, Placement.QUEUE_AFTER_SESSION,
                Priority.NORMAL, voice);
    }

    public static AXSpeechUtterance command(String text, Placement placement, Priority priority, VoiceProfile voice) {
        return new AXSpeechUtterance(text, 0, 0L, Mode.COMMAND, placement, priority, voice);
    }
}