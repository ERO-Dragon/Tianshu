package com.rheinmetal.tianshu.function.intake.repair.core;

import java.util.Set;

@FunctionalInterface
public interface ItemContextProvider {
    Set<Integer> getContextInternalIds();
}
