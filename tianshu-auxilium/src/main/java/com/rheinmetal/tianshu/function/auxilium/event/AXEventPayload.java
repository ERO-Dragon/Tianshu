package com.rheinmetal.tianshu.function.auxilium.event;

/**
 * Facts carried by one event occurrence.
 *
 * <p>Plain numbers only. The wording of the occurrence travels separately as {@link
 * AXEventDescriptor#text()}, because a rendered sentence is a producer concern and not a fact: keeping
 * it out of here means this record never has to be sanitized, translated or reformatted by the event
 * layer.
 *
 * @param count     how many of the thing happened inside this one event; at least {@code 1}
 * @param magnitude optional integer metric whose meaning is owned by the kind (for example hearts
 *                  lost, or current health after the drop); {@code null} when the kind carries no
 *                  numeric metric, which is also what an aggregate renderer receives for a window
 *                  whose occurrences carried none
 */
public record AXEventPayload(int count, Integer magnitude) {
    public AXEventPayload {
        count = Math.max(1, count);
    }

    /** One occurrence without a numeric metric. */
    public static AXEventPayload of() {
        return new AXEventPayload(1, null);
    }

    /** One occurrence carrying a numeric metric. */
    public static AXEventPayload ofMagnitude(Integer magnitude) {
        return new AXEventPayload(1, magnitude);
    }

    /** Several occurrences of the thing inside one event, carrying a numeric metric. */
    public static AXEventPayload of(int count, Integer magnitude) {
        return new AXEventPayload(count, magnitude);
    }
}
