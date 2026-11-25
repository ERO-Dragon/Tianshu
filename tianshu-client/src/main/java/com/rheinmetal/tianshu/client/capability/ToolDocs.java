package com.rheinmetal.tianshu.client.capability;

import com.rheinmetal.tianshu.api.capability.CapabilityId;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * A tool's own documentation, found from its id and nothing else.
 *
 * <p>{@code tianshu:body.mine} reads {@code tianshu/docs/tools/tianshu.body.mine.md}: one tool, one file, path derived
 * from the id. That is the whole correspondence — no index to keep in sync, no declaration to forget, and no way for the
 * tool and its document to disagree about which tool the document is about.</p>
 *
 * <p>Deliberately not a skill: a skill is prose a model reads to learn a calling convention, and it groups whatever its
 * author wants. A tool's documentation is not grouped — it belongs to that tool.</p>
 */
public final class ToolDocs {

    private static final String ROOT = "tianshu/docs/tools/";
    private static final String SUFFIX = ".md";
    private static final int MAX_CHARS = 24_000;

    private ToolDocs() {
    }

    /** The documentation for this tool, or empty when the tool ships without any. */
    public static Optional<String> of(CapabilityId id) {
        if (id == null) {
            return Optional.empty();
        }
        String resource = ROOT + id.toString().replace(':', '.') + SUFFIX;
        ClassLoader loader = ToolDocs.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
                return Optional.empty();
            }
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return Optional.of(text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text);
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    /** Where the documentation for this id would live; exposed for the error message when there is none. */
    public static String pathOf(CapabilityId id) {
        return ROOT + (id == null ? "" : id.toString().replace(':', '.')) + SUFFIX;
    }
}