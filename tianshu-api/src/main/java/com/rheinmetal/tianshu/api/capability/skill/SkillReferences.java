package com.rheinmetal.tianshu.api.capability.skill;

import com.rheinmetal.tianshu.api.capability.CapabilityId;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Where a tool's document is: the skill that ships it, found from the tool's id.
 *
 * <p>A tool's document is not a capability of its own — one row per tool is the whole point, and a second row per file
 * would double the table for nothing. It is an <em>attachment</em> of the tool, and the skill is the thing that carries
 * it: {@code references/} is the folder the skill format defines, so the file belongs to the skill that published it,
 * and asking for it needs no index that could drift from what is actually shipped.</p>
 *
 * <p>Lookup goes through the skill providers rather than the class loader. A class-path scan could only ever find the
 * base's own files; a mod that registers its own skill folder documents its tools in exactly the same way, and this is
 * the one place that has to know it. The providers are the authority on "does my folder contain this file", which also
 * keeps the answer working inside a jar, where a class loader cannot list a directory.</p>
 *
 * <p>This decides <em>who provides</em> the text, never <em>who may read</em> it: permission is the tool row's sharing
 * declaration, checked by the caller before it asks here. Otherwise the same bytes would carry two different answers.</p>
 */
public final class SkillReferences {

    private static final List<SkillFileProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    /** A tool's document, with the skill and the file it came from. */
    public record Document(CapabilityId skill, String path, String text) {
    }

    private SkillReferences() {
    }

    /** Starts offering this provider's references; the provider withdraws itself when it unloads. */
    static void publish(SkillFileProvider provider) {
        if (!PROVIDERS.contains(provider)) {
            PROVIDERS.add(provider);
        }
    }

    static void withdraw(SkillFileProvider provider) {
        PROVIDERS.remove(provider);
    }

    /**
     * The document for this tool, or empty when the skill that ships it has none.
     *
     * <p>Two spellings, tried in order: the bare name ({@code references/block.break.md}) and the fully qualified one
     * ({@code references/minecraft.block.break.md}). The bare name is the normal case — a skill belongs to one
     * namespace, so repeating it in every file name is noise — and the qualified form stays available for a module that
     * would rather be explicit.</p>
     */
    public static Optional<Document> of(CapabilityId toolId) {
        if (toolId == null) {
            return Optional.empty();
        }
        for (String file : fileNamesOf(toolId)) {
            for (SkillFileProvider provider : PROVIDERS) {
                Optional<Document> found = provider.reference(file);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    /** Where a document for this id would live; used when refusing, so the answer names a place that can be filled. */
    public static String pathOf(CapabilityId toolId) {
        return "references/" + (toolId == null ? "" : fileNamesOf(toolId).get(0));
    }

    /** {@code minecraft:block.break} → {@code block.break.md}, then {@code minecraft.block.break.md}. */
    private static List<String> fileNamesOf(CapabilityId id) {
        return List.of(id.name() + ".md", id.namespace() + "." + id.name() + ".md");
    }
}