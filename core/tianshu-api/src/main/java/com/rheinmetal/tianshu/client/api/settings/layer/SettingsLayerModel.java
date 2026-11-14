package com.rheinmetal.tianshu.client.api.settings.layer;

import com.rheinmetal.tianshu.client.api.text.UiText;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One layer, as a description: a title, its elements in reading order, and what to do when it closes.
 *
 * <p>A layer is a value. The business code builds it from its own state — suppliers and callbacks, never
 * copies — and hands it to a {@link SettingsLayerHost}, which is the only party that knows about a
 * window. Building the same layer twice from the same state therefore yields the same window, which is
 * what makes a layer testable without a game runtime.
 *
 * <p>{@code onClosed} runs exactly once, whichever way the layer was left: a button that declares
 * {@link SettingsLayerElement.Action#closesLayer()}, {@code Esc}, a click outside the panel, or the
 * window being replaced. A confirmed action runs before it, so "did the player accept" is answered by
 * the action's own callback and never by the close hook.
 *
 * @param title    heading of the panel
 * @param elements contents in reading order; at least the list or the buttons are usually present
 * @param onClosed what to do once the layer is gone; never {@code null}
 */
public record SettingsLayerModel(UiText title, List<SettingsLayerElement> elements, Runnable onClosed) {
    private static final Runnable NOTHING = () -> { };

    public SettingsLayerModel {
        title = title == null ? UiText.literal("") : title;
        List<SettingsLayerElement> normalized = new ArrayList<>();
        if (elements != null) {
            for (SettingsLayerElement element : elements) {
                if (element != null) {
                    normalized.add(element);
                }
            }
        }
        elements = List.copyOf(normalized);
        onClosed = onClosed == null ? NOTHING : onClosed;
    }

    /** A layer with the given title and elements and nothing to do on close. */
    public static SettingsLayerModel of(UiText title, SettingsLayerElement... elements) {
        return new SettingsLayerModel(title, List.of(elements), null);
    }

    /** A layer with the given title and elements and nothing to do on close. */
    public static SettingsLayerModel of(UiText title, List<SettingsLayerElement> elements) {
        return new SettingsLayerModel(title, elements, null);
    }

    /** The same layer, with this hook run once it is gone. */
    public SettingsLayerModel closedBy(Runnable onClosed) {
        return new SettingsLayerModel(title, elements, onClosed);
    }

    /**
     * One element by id.
     *
     * @return {@code null} when no element has that id
     */
    public SettingsLayerElement element(String id) {
        if (id == null) {
            return null;
        }
        for (SettingsLayerElement element : elements) {
            if (id.equals(element.id())) {
                return element;
            }
        }
        return null;
    }

    /**
     * One element by id and kind.
     *
     * @return {@code null} when the id is missing or holds another kind of element
     */
    public <T extends SettingsLayerElement> T element(String id, Class<T> kind) {
        SettingsLayerElement element = element(id);
        return kind != null && kind.isInstance(element) ? kind.cast(element) : null;
    }

    static String requireId(String id) {
        return Objects.requireNonNull(id, "id");
    }
}
