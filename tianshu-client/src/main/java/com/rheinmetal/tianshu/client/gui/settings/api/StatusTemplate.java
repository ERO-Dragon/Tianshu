package com.rheinmetal.tianshu.client.gui.settings.api;

import com.rheinmetal.tianshu.client.ui.UiText;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public interface StatusTemplate {
    StatusTemplate row(String id, UiText label, Supplier<UiText> value);

    StatusTemplate row(String id, UiText label, Supplier<UiText> value, BooleanSupplier enabled);

    StatusTemplate row(String id, UiText label, Supplier<UiText> value, BooleanSupplier enabled, BooleanSupplier visible);
}


