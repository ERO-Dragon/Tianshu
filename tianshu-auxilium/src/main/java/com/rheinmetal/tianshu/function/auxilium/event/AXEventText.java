package com.rheinmetal.tianshu.function.auxilium.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The wording of one event as a template key plus its arguments — the semantic form a producer submits.
 *
 * <p><strong>This type is render-time only and must never be put on the wire.</strong> The history entry
 * ({@link AXEventAppend}) carries the rendered sentence, because that is the only thing a later reader
 * needs and the only thing that stays valid when the language table changes. A future cross-process event
 * protocol has to send the resolved sentence (or its own schema), never this record: its arguments nest,
 * and nested language references only mean something inside the process that renders them.
 *
 * <p>An argument is either a {@link Arg.Value literal} — a number, a player name, an already localized
 * vanilla string — or a {@link Arg.Nested} reference to another {@link AXEventText}, which is how a detail
 * fragment ("lost 3 health", a dimension name) stays a template instead of becoming a sentence inside the
 * producer. Nesting is capped at {@link #MAX_REF_DEPTH} because a language table that references itself
 * would otherwise recurse without end.
 */
public record AXEventText(String key, List<Arg> args) {
    /** Deepest nesting a template may reach; a self referencing template stops here and renders empty. */
    public static final int MAX_REF_DEPTH = 4;

    public AXEventText {
        key = key == null ? "" : key.trim();
        args = args == null ? List.of() : List.copyOf(args);
    }

    /** A template with literal arguments only. */
    public static AXEventText of(String key, String... values) {
        List<Arg> arguments = new ArrayList<>(values == null ? 0 : values.length);
        if (values != null) {
            for (String value : values) {
                arguments.add(Arg.value(value));
            }
        }
        return new AXEventText(key, arguments);
    }

    /** A template whose arguments are built here; each may itself be a literal or a nested template. */
    public static AXEventText of(String key, List<Arg> args) {
        return new AXEventText(key, args);
    }

    public boolean blank() {
        return key.isEmpty();
    }

    /** One argument of a template; see the class comment for why nesting exists. */
    public sealed interface Arg permits Arg.Value, Arg.Nested {
        static Arg value(String text) {
            return new Value(text);
        }

        static Arg nested(AXEventText text) {
            return new Nested(text);
        }

        /** A concrete value substituted as is. */
        record Value(String text) implements Arg {
            public Value {
                text = text == null ? "" : text;
            }
        }

        /** Another template rendered first; its result becomes this argument. */
        record Nested(AXEventText text) implements Arg {
            public Nested {
                Objects.requireNonNull(text, "nested event text is required");
            }
        }
    }
}
