package com.rheinmetal.tianshu.client.capability;

import com.rheinmetal.tianshu.api.capability.CapabilityId;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * A tool's own document, found inside the skill that ships it.
 *
 * <p>{@code tianshu:body.mine} is {@code references/tianshu.body.mine.md} under one of the skills listed in
 * {@code tianshu/skills/index.txt}. Both halves are conventions taken from the skill format: documents live in a
 * {@code references/} folder, one file per topic, and the file is named after the tool it documents — so nothing has to
 * be declared, and no index can drift from what is actually shipped.</p>
 *
 * <p>Deliberately not a skill: a skill is prose a model reads to learn a calling convention and groups whatever its
 * author wants. A tool's document belongs to that tool.</p>
 */
public final class ToolDocs {

    private static final String SKILLS = "tianshu/skills/";
    private static final String INDEX = SKILLS + "index.txt";
    private static final String REFERENCES = "/references/";
    private static final String SUFFIX = ".md";
    private static final int MAX_CHARS = 48_000;

    private ToolDocs() {
    }

    /** The document for this tool, or empty when the tool ships without one. */
    public static Optional<String> of(CapabilityId id) {
        if (id == null) {
            return Optional.empty();
        }
        String file = fileNameOf(id);
        for (String skill : skills()) {
            String resource = SKILLS + skill + REFERENCES + file;
            ClassLoader loader = ToolDocs.class.getClassLoader();
            try (InputStream input = loader.getResourceAsStream(resource)) {
                if (input == null) {
                    continue;
                }
                String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                return Optional.of(text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text);
            } catch (IOException unreadable) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Where a document for this id would live; used in the refusal message when there is none. */
    public static String pathOf(CapabilityId id) {
        return SKILLS + "<skill>" + REFERENCES + (id == null ? "" : fileNameOf(id));
    }

    /** {@code tianshu:body.mine} → {@code tianshu.body.mine.md}. */
    private static String fileNameOf(CapabilityId id) {
        return id.toString().replace(':', '.') + SUFFIX;
    }

    /** The skill directories the base ships, in index order; unreadable index means no documents. */
    private static java.util.List<String> skills() {
        ClassLoader loader = ToolDocs.class.getClassLoader();
        try (InputStream index = loader.getResourceAsStream(INDEX)) {
            if (index == null) {
                return java.util.List.of();
            }
            java.util.List<String> names = new java.util.ArrayList<>();
            for (String line : new String(index.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String value = line.strip();
                if (!value.isEmpty() && !value.startsWith("#")) {
                    names.add(value);
                }
            }
            return java.util.List.copyOf(names);
        } catch (IOException unreadable) {
            return java.util.List.of();
        }
    }
}