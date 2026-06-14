package com.rheinmetal.tianshu.client.gui.settings.api;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public interface ListTemplate<T> {
    ListTemplate<T> items(Supplier<List<T>> items);

    ListTemplate<T> label(Function<T, Component> labeler);

    ListTemplate<T> card(Function<T, SettingsListCard> carder);

    ListTemplate<T> selected(Supplier<T> selected);

    ListTemplate<T> onSelect(Consumer<T> onSelect);

    ListTemplate<T> itemActions(BiConsumer<T, ItemActionTemplate<T>> builder);

    ListTemplate<T> emptyText(Component emptyText);

    ListTemplate<T> enabled(BooleanSupplier enabled);

    ListTemplate<T> visible(BooleanSupplier visible);
}

