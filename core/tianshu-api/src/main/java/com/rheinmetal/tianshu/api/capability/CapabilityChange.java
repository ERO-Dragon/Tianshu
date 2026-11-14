package com.rheinmetal.tianshu.api.capability;

/** One change to the capability table, delivered to in-process listeners. */
public record CapabilityChange(Action action, CapabilityDescriptor descriptor) {

    public enum Action {
        ADDED,
        UPDATED,
        REMOVED
    }

    @FunctionalInterface
    public interface Listener {
        void onChange(CapabilityChange change);
    }
}
