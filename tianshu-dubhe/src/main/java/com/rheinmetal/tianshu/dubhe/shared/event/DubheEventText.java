package com.rheinmetal.tianshu.dubhe.shared.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The wording of one event as a template key plus its arguments — the semantic form a producer submits.
 *
 * <p><strong>This type is render-time only and must never be put on the wire.</strong> The history entry
 * ({@link DubheEventAppend}) carries the rendered sentence, because that is the only thing a later reader
 * needs and the only thing that stays valid when the language table changes. A future cross-process event
 * protocol has to send the resolved sentence (or its own schema), never this record: its arguments nest,
 * and nested language references only mean something inside the process that renders them.
 *
 * <p>An argument is one of three things: a {@link Arg.Value literal} — a number, a player name, an
 * already localized vanilla string; a {@link Arg.Nested} reference to another {@link DubheEventText},
 * which is how a detail fragment ("lost 3 health", a dimension name) stays a template instead of becoming
 * a sentence on its own; or a {@link Arg.Key language reference} for a token only the language table can
 * word ("rain", "minecraft:overworld"). Nesting is capped at {@link #MAX_REF_DEPTH} because a language
 * table that references itself would otherwise recurse without end.
 */
public record DubheEventText(String key, List<Arg> args) {
    /** Deepest nesting a template may reach; a self referencing template stops here and renders empty. */
    public static final int MAX_REF_DEPTH = 4;

    public DubheEventText {
        key = key == null ? "" : key.trim();
        args = args == null ? List.of() : List.copyOf(args);
    }

    /** A template with literal arguments only. */
    public static DubheEventText of(String key, String... values) {
        List<Arg> arguments = new ArrayList<>(values == null ? 0 : values.length);
        if (values != null) {
            for (String value : values) {
                arguments.add(Arg.value(value));
            }
        }
        return new DubheEventText(key, arguments);
    }

    /** A template whose arguments are built here; each may itself be a literal or a nested template. */
    public static DubheEventText of(String key, List<Arg> args) {
        return new DubheEventText(key, args);
    }

    public boolean blank() {
        return key.isEmpty();
    }

    /** One argument of a template; see the class comment for why three kinds exist. */
    public sealed interface Arg permits Arg.Value, Arg.Nested, Arg.Key {
        static Arg value(String text) {
            return new Value(text);
        }

        static Arg nested(DubheEventText text) {
            return new Nested(text);
        }

        /**
         * A language key resolved at render time, with the raw token as its fallback.
         *
         * <p>Some values a producer can only state as a token — a weather state, a dimension id — and putting
         * that token straight into a sentence is how "weather changed: rain" happens inside a translated line. Naming
         * a key instead keeps the wording in the language table; the fallback covers the case a key does not
         * exist yet (a modded dimension), where the raw token still says something true.</p>
         *
         * @param key      language key to render
         * @param fallback token shown when that key is missing; blank means the key is required
         */
        static Arg key(String key, String fallback) {
            return new Key(key, fallback);
        }

        /** A concrete value substituted as is. */
        record Value(String text) implements Arg {
            public Value {
                text = text == null ? "" : text;
            }
        }

        /** Another template rendered first; its result becomes this argument. */
        record Nested(DubheEventText text) implements Arg {
            public Nested {
                Objects.requireNonNull(text, "nested event text is required");
            }
        }

        /** A language key resolved at render time; see {@link #key(String, String)}. */
        record Key(String key, String fallback) implements Arg {
            public Key {
                key = normalize(key);
                fallback = fallback == null ? "" : fallback;
            }
        }
    }

    private static String normalize(String key) {
        return key == null ? "" : key.trim();
    }
}
