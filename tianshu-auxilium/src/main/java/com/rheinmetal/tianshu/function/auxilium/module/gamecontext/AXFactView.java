package com.rheinmetal.tianshu.function.auxilium.module.gamecontext;

import com.rheinmetal.tianshu.protocol.payload.FactField;
import com.rheinmetal.tianshu.protocol.payload.FactValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only view over one fact's typed fields.
 *
 * <p>The prompt formatter used to read a flat {@code Map<String, String>} that the producer built next to
 * the typed fields. Two encodings of the same fact can drift apart — a filter or a truncation added to one
 * would silently change what the model reads — so the formatter now reads the fields themselves, and this
 * view is the only place that knows how a typed value becomes prompt text.</p>
 *
 * <p>Scalar values render exactly as the flat map used to: numbers with one decimal ({@code 20.0}), counts
 * as integers, identifiers and text trimmed, flags as {@code true}/{@code false}. That equivalence is what
 * keeps the rendered prompt byte-identical across the migration.</p>
 *
 * <p>Repeated fields (inventory items, active effects) are grouped by name, because a record list is how
 * the producer expresses "many of these" — there is no JSON blob to parse here.</p>
 */
final class AXFactView {
    private final Map<String, FactValue> scalars;
    private final Map<String, List<Map<String, FactValue>>> records;

    private AXFactView(
            Map<String, FactValue> scalars,
            Map<String, List<Map<String, FactValue>>> records
    ) {
        this.scalars = scalars;
        this.records = records;
    }

    /** Groups the fields of one fact; repeated names become record lists, the rest stay scalars. */
    static AXFactView of(List<FactField> fields) {
        Map<String, FactValue> scalars = new LinkedHashMap<>();
        Map<String, List<Map<String, FactValue>>> records = new LinkedHashMap<>();
        if (fields != null) {
            for (FactField field : fields) {
                if (field == null || field.name() == null || field.value() == null) {
                    continue;
                }
                if (field.value() instanceof FactValue.Record record) {
                    records.computeIfAbsent(field.name(), ignored -> new ArrayList<>()).add(members(record.fields()));
                } else {
                    scalars.put(field.name(), field.value());
                }
            }
        }
        return new AXFactView(scalars, records);
    }

    /** Whether the fact carries anything at all; an empty fact is dropped instead of rendered blank. */
    boolean isEmpty() {
        return scalars.isEmpty() && records.isEmpty();
    }

    /** Prompt text of one scalar field, empty when the producer did not supply it. */
    String text(String name) {
        return format(scalars.get(name));
    }

    /** The repeated records filed under {@code name}, in the order the producer wrote them. */
    List<Map<String, FactValue>> records(String name) {
        return records.getOrDefault(name, List.of());
    }

    /** Prompt text of one record member, empty when absent. */
    static String memberText(Map<String, FactValue> record, String name) {
        return format(record.get(name));
    }

    /** Integer member of a record, falling back when the producer left it out. */
    static int memberInt(Map<String, FactValue> record, String name, int fallback) {
        FactValue value = record.get(name);
        if (value instanceof FactValue.Count count) {
            return count.value();
        }
        if (value instanceof FactValue.Number number) {
            return (int) number.value();
        }
        if (value instanceof FactValue.Text text) {
            try {
                return Integer.parseInt(text.value().trim());
            } catch (RuntimeException notANumber) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Map<String, FactValue> members(List<FactField> fields) {
        Map<String, FactValue> members = new LinkedHashMap<>();
        if (fields != null) {
            for (FactField field : fields) {
                if (field != null && field.name() != null && field.value() != null) {
                    members.putIfAbsent(field.name(), field.value());
                }
            }
        }
        return members;
    }

    private static String format(FactValue value) {
        if (value instanceof FactValue.Number number) {
            return String.format(Locale.ROOT, "%.1f", number.value());
        }
        if (value instanceof FactValue.Count count) {
            return Integer.toString(count.value());
        }
        if (value instanceof FactValue.Identifier identifier) {
            return trim(identifier.value());
        }
        if (value instanceof FactValue.Text text) {
            return trim(text.value());
        }
        if (value instanceof FactValue.Flag flag) {
            return Boolean.toString(flag.value());
        }
        return "";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
