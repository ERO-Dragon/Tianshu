package com.rheinmetal.tianshu.function.ir.core;

import java.util.Set;

@FunctionalInterface
public interface ItemContextProvider {
    Set<Integer> getContextInternalIds();
}
