package com.rheinmetal.tianshu.client.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public record UiText(String value, List<Object> arguments, boolean translatable, List<UiText> parts) {
    public UiText {
        value = value == null ? "" : value;
        parts = parts == null ? List.of() : List.copyOf(parts.stream().filter(part -> part != null).toList());
        if (parts.isEmpty()) {
            arguments = translatable ? normalizeArguments(arguments) : List.of();
        } else {
            value = "";
            arguments = List.of();
            translatable = false;
        }
    }

    public static UiText key(String key, Object... arguments) {
        return new UiText(key, arguments == null ? List.of() : Arrays.asList(arguments.clone()), true, List.of());
    }

    public static UiText literal(String value) {
        return new UiText(value, List.of(), false, List.of());
    }

    public static UiText join(String separator, List<UiText> texts) {
        if (texts == null || texts.isEmpty()) {
            return literal("");
        }
        List<UiText> normalized = texts.stream().filter(text -> text != null).toList();
        if (normalized.isEmpty()) {
            return literal("");
        }
        if (normalized.size() == 1) {
            return normalized.getFirst();
        }
        UiText delimiter = literal(separator);
        List<UiText> parts = new ArrayList<>(normalized.size() * 2 - 1);
        for (UiText text : normalized) {
            if (!parts.isEmpty()) {
                parts.add(delimiter);
            }
            parts.add(text);
        }
        return new UiText("", List.of(), false, parts);
    }

    public boolean composite() {
        return !parts.isEmpty();
    }

    public boolean isBlank() {
        if (composite()) {
            return parts.stream().allMatch(UiText::isBlank);
        }
        return value.isBlank();
    }

    private static List<Object> normalizeArguments(List<Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return List.of();
        }
        List<Object> normalized = new ArrayList<>(arguments.size());
        for (Object argument : arguments) {
            normalized.add(normalizeArgument(argument));
        }
        return List.copyOf(normalized);
    }

    private static Object normalizeArgument(Object argument) {
        if (argument == null) {
            return "";
        }
        if (argument instanceof UiText
                || argument instanceof String
                || argument instanceof Number
                || argument instanceof Boolean
                || argument instanceof Character) {
            return argument;
        }
        if (argument instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return String.valueOf(argument);
    }
}
