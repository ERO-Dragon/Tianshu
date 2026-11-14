package com.rheinmetal.tianshu.client.api.settings;

import com.rheinmetal.tianshu.client.api.text.UiText;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public interface ListTemplate<T> {
    ListTemplate<T> items(Supplier<List<T>> items);

    ListTemplate<T> label(Function<T, UiText> labeler);

    ListTemplate<T> card(Function<T, SettingsListCard> carder);

    ListTemplate<T> selected(Supplier<T> selected);

    ListTemplate<T> onSelect(Consumer<T> onSelect);

    ListTemplate<T> itemActions(BiConsumer<T, ItemActionTemplate<T>> builder);

    /**
     * Buttons rendered at the bottom of the list, inside the same section as the items.
     *
     * <p>It is where a list owns its own "add" affordance: a button inside the list section reads as
     * part of that list, while a separate actions section would sit between the list and whatever the
     * player opens from a row — the editor a card expands, for example.</p>
     */
    ListTemplate<T> footerActions(Consumer<ActionTemplate> builder);

    ListTemplate<T> emptyText(UiText emptyText);

    ListTemplate<T> enabled(BooleanSupplier enabled);

    ListTemplate<T> visible(BooleanSupplier visible);
}

