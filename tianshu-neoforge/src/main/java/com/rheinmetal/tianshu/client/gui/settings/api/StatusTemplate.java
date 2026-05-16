package com.rheinmetal.tianshu.client.gui.settings.api;

import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public interface StatusTemplate {
    StatusTemplate row(String id, Component label, Supplier<Component> value);

    StatusTemplate row(String id, Component label, Supplier<Component> value, BooleanSupplier enabled);

    StatusTemplate row(String id, Component label, Supplier<Component> value, BooleanSupplier enabled, BooleanSupplier visible);
}


