package com.rheinmetal.tianshu.function.auxilium.producer;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventAppend;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventDescriptor;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventPayload;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventSpeechSink;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventText;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rendering contract of the event layer: bounded nesting, and never an empty history line.
 *
 * <p>Both cases come from the same refactor. Moving wording to the receiving side means a template can
 * reference another template, so a misconfigured language table can reference itself; and it means a
 * producer can no longer notice "I cannot word this" before submitting, so the receiver has to decide what
 * an unrenderable event looks like.
 */
class AXEventTextRenderingTest {
    private final List<AXEventAppend> appended = new ArrayList<>();
    private final List<String> logs = new ArrayList<>();

    /** Echoes every template as its key plus its arguments, so nesting is observable. */
    private static final AXGameEventTextProvider ECHO = new AXGameEventTextProvider() {
        @Override
        public boolean exists(String key) {
            return true;
        }

        @Override
        public String text(String key, Object... args) {
            StringBuilder rendered = new StringBuilder(key);
            for (Object arg : args) {
                rendered.append('[').append(arg).append(']');
            }
            return rendered.toString();
        }
    };

    @Test
    void aSelfReferencingTemplateStopsAtTheDepthCapInsteadOfOverflowingTheStack() {
        AXEventText selfReferencing = nestedSelfReference(0);

        String rendered = ECHO.render(selfReferencing);

        assertEquals("", rendered,
                "a template that never bottoms out must render blank instead of recursing forever");
    }

    @Test
    void nestingWithinTheCapStillRenders() {
        AXEventText inner = AXEventText.of("inner");
        AXEventText outer = AXEventText.of("outer", List.of(AXEventText.Arg.nested(inner)));

        assertEquals("outer[inner]", ECHO.render(outer));
    }

    @Test
    void anUnwordableEventEntersTheHistoryAsItsKindCodeAndNeverAsBlank() {
        AXGameEventTextProvider missingKey = new AXGameEventTextProvider() {
            @Override
            public boolean exists(String key) {
                return false;
            }

            @Override
            public String text(String key, Object... args) {
                return "";
            }
        };
        AXGameEventService service = new AXGameEventService(
                appended::add,
                AXEventSpeechSink.NOOP,
                missingKey,
                new RecordingLog(logs)
        );

        service.submit(AXEventDescriptor.of("tianshu:death", "module.probe", AXEventPayload.of(),
                AXEventText.of("tianshu.ax.event.line.tianshu.death")), 1_000L);

        assertEquals(1, appended.size(), "the entry is still recorded");
        AXEventAppend entry = appended.get(0);
        assertEquals("tianshu:death", entry.text(),
                "an unwordable event must occupy its slot with the kind code, never with an empty line");
        assertTrue(logs.stream().anyMatch(line -> line.contains("ax.event.text.unresolved")),
                "falling back has to be observable: " + logs);
    }

    @Test
    void aRenderedEventKeepsItsOwnSentence() {
        AXGameEventService service = new AXGameEventService(
                appended::add,
                AXEventSpeechSink.NOOP,
                ECHO,
                new RecordingLog(logs)
        );

        service.submit(AXEventDescriptor.of("tianshu:mob_killed", "module.probe", AXEventPayload.of(),
                AXEventText.of("line.mob_killed")), 1_000L);

        assertEquals("line.mob_killed", appended.get(0).text());
        assertTrue(logs.stream().noneMatch(line -> line.contains("ax.event.text.unresolved")),
                "a resolvable template reports no fallback: " + logs);
    }

    private static AXEventText nestedSelfReference(int depth) {
        if (depth >= AXEventText.MAX_REF_DEPTH + 2) {
            return AXEventText.of("loop");
        }
        return AXEventText.of("loop", List.of(AXEventText.Arg.nested(nestedSelfReference(depth + 1))));
    }

    private record RecordingLog(List<String> lines) implements LogSink {
        @Override
        public void info(String message) {
            lines.add(message);
        }

        @Override
        public void warn(String message) {
            lines.add(message);
        }

        @Override
        public void error(String message, Throwable failure) {
            lines.add(message);
        }
    }
}
