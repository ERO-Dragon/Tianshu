package com.rheinmetal.tianshu.intake.repair.core;

import java.util.Set;

@FunctionalInterface
public interface ItemContextProvider {
    Set<Integer> getContextInternalIds();
}
