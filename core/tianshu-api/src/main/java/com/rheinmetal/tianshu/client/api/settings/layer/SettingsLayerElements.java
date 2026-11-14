package com.rheinmetal.tianshu.client.api.settings.layer;

import com.rheinmetal.tianshu.client.api.settings.SettingsButtonStyle;
import com.rheinmetal.tianshu.client.api.text.UiText;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Short constructors for the four element kinds, so a layer description reads like a layout.
 *
 * <p>Every factory takes live suppliers and callbacks; nothing is copied at build time.
 */
public final class SettingsLayerElements {
    private static final BooleanSupplier ALWAYS = () -> true;
    private static final UiText NO_EMPTY_TEXT = UiText.key("tianshu.gui.common.no_available_items");

    private SettingsLayerElements() {
    }

    /** One labelled input line. */
    public static SettingsLayerElement.Text text(String id, UiText label, UiText hint,
                                                 Supplier<String> value, Consumer<String> setter) {
        return new SettingsLayerElement.Text(id, label, hint, value, setter);
    }

    /** A list without a search field. */
    public static SettingsLayerElement.List list(String id, UiText title, Supplier<List<String>> items,
                                                 Supplier<String> selected, Consumer<String> onSelect) {
        return new SettingsLayerElement.List(id, title, NO_EMPTY_TEXT, items, selected, onSelect, null);
    }

    /** A list driven by its own search field; the framework applies the term to {@code items}. */
    public static SettingsLayerElement.List searchableList(String id, UiText title, UiText emptyText,
                                                           Supplier<List<String>> items, Supplier<String> selected,
                                                           Consumer<String> onSelect, UiText searchLabel,
                                                           Supplier<String> term, Consumer<String> setTerm) {
        SettingsLayerElement.List.Search search = new SettingsLayerElement.List.Search(searchLabel, term, setTerm);
        return new SettingsLayerElement.List(id, title, emptyText, items, selected, onSelect, search);
    }

    /** A row of buttons. */
    public static SettingsLayerElement.Actions actions(String id, SettingsLayerElement.Action... actions) {
        return new SettingsLayerElement.Actions(id, List.of(actions));
    }

    /** One button that stays in the layer when it is clicked. */
    public static SettingsLayerElement.Action action(String id, UiText label, BooleanSupplier enabled, Runnable run) {
        return new SettingsLayerElement.Action(id, label, SettingsButtonStyle.NORMAL, enabled, run, false);
    }

    /** One button, with its style and whether a click closes the layer. */
    public static SettingsLayerElement.Action action(String id, UiText label, SettingsButtonStyle style,
                                                     BooleanSupplier enabled, Runnable run, boolean closesLayer) {
        return new SettingsLayerElement.Action(id, label, style, enabled, run, closesLayer);
    }

    /** One read-only line. */
    public static SettingsLayerElement.Status status(String id, Supplier<UiText> text) {
        return new SettingsLayerElement.Status(id, text);
    }

    /** A predicate that is always true, for buttons that are always available. */
    public static BooleanSupplier alwaysEnabled() {
        return ALWAYS;
    }
}
