package com.rheinmetal.tianshu.function.auxilium.memory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.rheinmetal.tianshu.function.auxilium.storage.AXHashing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AXMemoryFactExtractionParser {
    private static final int MAX_FACT_CHARS = 512;
    private static final String FACT_KEY = "fact";

    public List<String> parse(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(normalized);
        } catch (Exception e) {
            return List.of();
        }
        if (root == null || !root.isJsonArray()) {
            return List.of();
        }
        List<String> facts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonElement element : root.getAsJsonArray()) {
            String fact = readFact(element);
            if (fact.isBlank() || fact.length() > MAX_FACT_CHARS || fact.indexOf('\uFFFD') >= 0) {
                continue;
            }
            if (seen.add(AXHashing.sha256Short(fact))) {
                facts.add(fact);
            }
        }
        return facts;
    }

    private String readFact(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return "";
        }
        JsonObject object = element.getAsJsonObject();
        if (object.size() != 1 || !object.has(FACT_KEY) || object.get(FACT_KEY).isJsonNull() || !object.get(FACT_KEY).isJsonPrimitive()) {
            return "";
        }
        JsonPrimitive primitive = object.get(FACT_KEY).getAsJsonPrimitive();
        if (!primitive.isString()) {
            return "";
        }
        String value = primitive.getAsString();
        return value == null ? "" : value.trim();
    }
}
