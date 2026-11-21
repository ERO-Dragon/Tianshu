package com.rheinmetal.tianshu.dubhe.producer;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.dubhe.event.DubheEventAppend;
import com.rheinmetal.tianshu.dubhe.event.DubheEventDescriptor;
import com.rheinmetal.tianshu.dubhe.event.DubheEventPayload;
import com.rheinmetal.tianshu.dubhe.event.DubheEventSpeechSink;
import com.rheinmetal.tianshu.dubhe.event.DubheEventText;
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
class DubheEventTextRenderingTest {
    private final List<DubheEventAppend> appended = new ArrayList<>();
    private final List<String> logs = new ArrayList<>();

    /** Echoes every template as its key plus its arguments, so nesting is observable. */
    private static final DubheGameEventTextProvider ECHO = new DubheGameEventTextProvider() {
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
        DubheEventText selfReferencing = nestedSelfReference(0);

        String rendered = ECHO.render(selfReferencing);

        assertEquals("", rendered,
                "a template that never bottoms out must render blank instead of recursing forever");
    }

    @Test
    void nestingWithinTheCapStillRenders() {
        DubheEventText inner = DubheEventText.of("inner");
        DubheEventText outer = DubheEventText.of("outer", List.of(DubheEventText.Arg.nested(inner)));

        assertEquals("outer[inner]", ECHO.render(outer));
    }

    @Test
    void anUnwordableEventEntersTheHistoryAsItsKindCodeAndNeverAsBlank() {
        DubheGameEventTextProvider missingKey = new DubheGameEventTextProvider() {
            @Override
            public boolean exists(String key) {
                return false;
            }

            @Override
            public String text(String key, Object... args) {
                return "";
            }
        };
        DubheGameEventService service = new DubheGameEventService(
                appended::add,
                DubheEventSpeechSink.NOOP,
                missingKey,
                new RecordingLog(logs)
        );

        // Highlighted so the event takes the immediate class: this case is about the fallback wording, not about the
        // tier table, and an immediate event is the one that reaches the history on arrival.
        service.submit(DubheEventDescriptor.of("tianshu:death", "module.probe", DubheEventPayload.of(),
                DubheEventText.of("tianshu.ax.event.line.tianshu.death")).withHighlight(true), 1_000L);

        assertEquals(1, appended.size(), "the entry is still recorded");
        DubheEventAppend entry = appended.get(0);
        assertEquals("tianshu:death", entry.text(),
                "an unwordable event must occupy its slot with the kind code, never with an empty line");
        assertTrue(logs.stream().anyMatch(line -> line.contains("ax.event.text.unresolved")),
                "falling back has to be observable: " + logs);
    }

    @Test
    void aRenderedEventKeepsItsOwnSentence() {
        DubheGameEventService service = new DubheGameEventService(
                appended::add,
                DubheEventSpeechSink.NOOP,
                ECHO,
                new RecordingLog(logs)
        );

        service.submit(DubheEventDescriptor.of("tianshu:mob_killed", "module.probe", DubheEventPayload.of(),
                DubheEventText.of("line.mob_killed")), 1_000L);

        assertEquals("line.mob_killed", appended.get(0).text());
        assertTrue(logs.stream().noneMatch(line -> line.contains("ax.event.text.unresolved")),
                "a resolvable template reports no fallback: " + logs);
    }

    private static DubheEventText nestedSelfReference(int depth) {
        if (depth >= DubheEventText.MAX_REF_DEPTH + 2) {
            return DubheEventText.of("loop");
        }
        return DubheEventText.of("loop", List.of(DubheEventText.Arg.nested(nestedSelfReference(depth + 1))));
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
