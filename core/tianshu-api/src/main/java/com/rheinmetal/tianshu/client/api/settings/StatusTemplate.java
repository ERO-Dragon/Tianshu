package com.rheinmetal.tianshu.client.api.settings;

import com.rheinmetal.tianshu.client.api.text.UiText;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public interface StatusTemplate {
    StatusTemplate row(String id, UiText label, Supplier<UiText> value);

    StatusTemplate row(String id, UiText label, Supplier<UiText> value, BooleanSupplier enabled);

    StatusTemplate row(String id, UiText label, Supplier<UiText> value, BooleanSupplier enabled, BooleanSupplier visible);
}


