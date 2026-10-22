package com.rheinmetal.tianshu.client.event;

/**
 * A transition the probe detected on one sample.
 *
 * <p>Carries numbers only: wording is resolved where the language files live, so this layer never
 * holds player facing text.
 *
 * @param kind  event kind, one of {@link AXGameEventKinds}
 * @param value meaning depends on the kind - the current metric for a critical crossing, the lost
 *              amount for a change kind, and {@code 0} for the transition kinds that carry no metric
 */
public record AXGameEventSignal(String kind, int value) {
}
