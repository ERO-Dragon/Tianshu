package com.rheinmetal.tianshu.ir;

import java.util.Set;

@FunctionalInterface
public interface ItemContextProvider {
    Set<Integer> getContextInternalIds();
}
