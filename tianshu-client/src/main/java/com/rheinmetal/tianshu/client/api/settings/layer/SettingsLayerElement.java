package com.rheinmetal.tianshu.client.api.settings.layer;

import com.rheinmetal.tianshu.client.api.settings.SettingsButtonStyle;
import com.rheinmetal.tianshu.client.api.text.UiText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One element of a {@link SettingsLayerModel}: the whole vocabulary a layer has.
 *
 * <p>The four kinds are the four things a layer is made of — a labelled input, a searchable list, a row
 * of buttons and a read-only line. They are deliberately suppliers and sinks rather than snapshots: a
 * window reads them again on every frame, so a value that changes underneath (a search term, a provider
 * answer) shows up without rebuilding the window and without losing the caret.
 *
 * <p>Nothing here knows about a screen, a widget or a game type: a layer is a description, and the
 * platform layer draws it.
 */
public sealed interface SettingsLayerElement permits
        SettingsLayerElement.Text,
        SettingsLayerElement.List,
        SettingsLayerElement.Actions,
        SettingsLayerElement.Status {

    /** Stable id of this element inside its layer; the platform layer uses it to keep widget state. */
    String id();

    /**
     * One labelled input line.
     *
     * @param label  the label above the field
     * @param hint   placeholder shown while the field is empty; may be blank
     * @param value  current text, read on every frame and after every action
     * @param setter receives every keystroke
     */
    record Text(String id, UiText label, UiText hint, Supplier<String> value, Consumer<String> setter)
            implements SettingsLayerElement {
        public Text {
            id = SettingsLayerModel.requireId(id);
            label = label == null ? UiText.literal("") : label;
            hint = hint == null ? UiText.literal("") : hint;
            value = value == null ? () -> "" : value;
            setter = setter == null ? ignored -> { } : setter;
        }

        /** Current value, never {@code null}. */
        public String currentValue() {
            String current = value.get();
            return current == null ? "" : current;
        }
    }

    /**
     * A selectable list of plain values, optionally narrowed by a search field.
     *
     * <p>The filter is defined here rather than by each caller: {@link #visibleItems()} keeps the values
     * whose text contains the search term, ignoring case, so every searchable list in the framework
     * behaves the same way. A caller hands over the whole source and one term, nothing else.
     *
     * @param title     heading above the list
     * @param emptyText shown instead of rows while nothing is listed
     * @param items     the whole source, read on every frame
     * @param selected  the value currently marked as chosen; may be blank
     * @param onSelect  receives a click on a row
     * @param search    the search field above the list, or {@code null} for a list without one
     */
    record List(String id, UiText title, UiText emptyText, Supplier<java.util.List<String>> items,
                Supplier<String> selected, Consumer<String> onSelect, Search search)
            implements SettingsLayerElement {
        public List {
            id = SettingsLayerModel.requireId(id);
            title = title == null ? UiText.literal("") : title;
            emptyText = emptyText == null ? UiText.literal("") : emptyText;
            items = items == null ? java.util.List::of : items;
            selected = selected == null ? () -> "" : selected;
            onSelect = onSelect == null ? ignored -> { } : onSelect;
        }

        /** Whether this list carries a search field. */
        public boolean searchable() {
            return search != null;
        }

        /** Current search term, empty when the list is not searchable. */
        public String term() {
            if (search == null) {
                return "";
            }
            Supplier<String> current = search.term();
            String value = current == null ? "" : current.get();
            return value == null ? "" : value;
        }

        /** The source narrowed by {@link #term()}: what a window lists right now. */
        public java.util.List<String> visibleItems() {
            java.util.List<String> source = items.get();
            if (source == null || source.isEmpty()) {
                return java.util.List.of();
            }
            String term = term().trim().toLowerCase(Locale.ROOT);
            java.util.List<String> visible = new ArrayList<>(source.size());
            for (String item : source) {
                if (item == null || item.isBlank()) {
                    continue;
                }
                if (term.isEmpty() || item.toLowerCase(Locale.ROOT).contains(term)) {
                    visible.add(item);
                }
            }
            return java.util.List.copyOf(visible);
        }

        /** Current selection, never {@code null}. */
        public String currentSelection() {
            String current = selected.get();
            return current == null ? "" : current;
        }

        /**
         * The search field of one list.
         *
         * @param label   the label above the field
         * @param term    current term, read on every frame
         * @param setTerm receives every keystroke
         */
        public record Search(UiText label, Supplier<String> term, Consumer<String> setTerm) {
            public Search {
                label = label == null ? UiText.literal("") : label;
                term = term == null ? () -> "" : term;
                setTerm = setTerm == null ? ignored -> { } : setTerm;
            }

            /** Current term, never {@code null}. */
            public String currentTerm() {
                String current = term.get();
                return current == null ? "" : current;
            }
        }
    }

    /**
     * A row of buttons.
     *
     * <p>Each button reads its own enabled predicate on every frame, so a button whose action is only
     * possible in some states greys out instead of disappearing: the row never moves under the cursor.
     */
    record Actions(String id, java.util.List<Action> actions) implements SettingsLayerElement {
        public Actions {
            id = SettingsLayerModel.requireId(id);
            actions = actions == null ? java.util.List.of() : java.util.List.copyOf(actions);
        }
    }

    /**
     * One button of {@link Actions}.
     *
     * @param label       player-facing label
     * @param style       {@link SettingsButtonStyle#NORMAL} when {@code null}
     * @param enabled     whether the button is clickable right now
     * @param run         what the click does; it runs before the layer closes
     * @param closesLayer whether a click closes the layer — a description never holds a window reference,
     *                    so closing is declared instead of called
     */
    record Action(String id, UiText label, SettingsButtonStyle style, BooleanSupplier enabled, Runnable run,
                  boolean closesLayer) {
        public Action {
            id = SettingsLayerModel.requireId(id);
            label = label == null ? UiText.literal("") : label;
            style = style == null ? SettingsButtonStyle.NORMAL : style;
            enabled = enabled == null ? () -> true : enabled;
            run = run == null ? () -> { } : run;
        }

        /** Whether the button is clickable right now. */
        public boolean isEnabled() {
            return enabled.getAsBoolean();
        }
    }

    /**
     * One read-only line, read on every frame.
     *
     * <p>Its space is reserved whether or not it says anything, so a layer does not jump when a status
     * appears.
     */
    record Status(String id, Supplier<UiText> text) implements SettingsLayerElement {
        public Status {
            id = SettingsLayerModel.requireId(id);
            text = text == null ? () -> UiText.literal("") : text;
        }

        /** Current text, never {@code null}; blank hides the line's content. */
        public UiText currentText() {
            UiText current = text.get();
            return current == null ? UiText.literal("") : current;
        }
    }
}
