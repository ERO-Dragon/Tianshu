package com.rheinmetal.tianshu.dubhe.shared.event;

/**
 * The one table that turns a frequency row and an attention state into a handling class.
 *
 * <p>Nine cells, one fixed value each (the anti-diagonal):</p>
 *
 * <pre>
 *            EMPHASIS   NORMAL    WEAKENED
 *   RARE     IMMEDIATE  DEFERRED  SILENT
 *   NORMAL   DEFERRED   SILENT    FOLD
 *   FREQUENT SILENT     FOLD      DROP
 * </pre>
 *
 * <p>Every kind declares its tier once, plus one state <em>per reachable row</em>. The frequency gate only moves the
 * row; the state travels with the kind. That is why a frequent event cannot turn into speech no matter what it
 * declares: the {@code FREQUENT} row contains no speaking class at all (H1).</p>
 */
public final class DubheEventPolicy {

    private DubheEventPolicy() {
    }

    /** A pure lookup; unknown inputs fall back to the most conservative cell. */
    public static DubheEventClass classFor(DubheEventTier row, DubheEventState state) {
        DubheEventTier effectiveRow = row == null ? DubheEventTier.FREQUENT : row;
        DubheEventState effectiveState = state == null ? DubheEventState.NORMAL : state;
        return switch (effectiveRow) {
            case RARE -> switch (effectiveState) {
                case EMPHASIS -> DubheEventClass.IMMEDIATE;
                case NORMAL -> DubheEventClass.DEFERRED;
                case WEAKENED -> DubheEventClass.SILENT;
            };
            case NORMAL -> switch (effectiveState) {
                case EMPHASIS -> DubheEventClass.DEFERRED;
                case NORMAL -> DubheEventClass.SILENT;
                case WEAKENED -> DubheEventClass.FOLD;
            };
            case FREQUENT -> switch (effectiveState) {
                case EMPHASIS -> DubheEventClass.SILENT;
                case NORMAL -> DubheEventClass.FOLD;
                case WEAKENED -> DubheEventClass.DROP;
            };
        };
    }

    /**
     * The inverse for external producers: what a module must declare to obtain {@code requested} on a neutral row.
     *
     * <p>The inverse is not unique in general; the plainest cell is chosen so the frequency gate and per-occurrence
     * flags still have room to move the result.</p>
     *
     * @throws IllegalArgumentException for {@link DubheEventClass#DROP}, which has no declaration: a producer that
     *                                  wants nothing simply does not submit
     */
    public static Declaration declarationFor(DubheEventClass requested) {
        if (requested == null || requested == DubheEventClass.DROP) {
            throw new IllegalArgumentException("DROP cannot be requested: do not submit the event instead");
        }
        return switch (requested) {
            case IMMEDIATE -> new Declaration(DubheEventTier.RARE, DubheEventState.EMPHASIS);
            case DEFERRED -> new Declaration(DubheEventTier.RARE, DubheEventState.NORMAL);
            case SILENT -> new Declaration(DubheEventTier.NORMAL, DubheEventState.NORMAL);
            case FOLD -> new Declaration(DubheEventTier.NORMAL, DubheEventState.WEAKENED);
            case DROP -> throw new IllegalArgumentException("unreachable");
        };
    }

    /** A producer-facing declaration: which row to declare and which state to ask for. */
    public record Declaration(DubheEventTier tier, DubheEventState state) {
    }
}