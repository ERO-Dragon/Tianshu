package com.rheinmetal.tianshu.api.assembly;

import java.util.List;

/**
 * The ids of every installed mod, in load order.
 *
 * <p>A module that walks mod content — reading guides, data files or anything else a partner ships inside its
 * jar — needs the list before it can ask for any of it. {@link InstalledMods} answers one id at a time, which
 * is enough for "is my optional partner here" but not for "show me everything that ships a book".</p>
 *
 * <p>Implementations are provided by the platform adapter and registered before module assembly. When nothing is
 * registered — the unit-test or headless case — {@link #none()} reports no mods at all, which is the safe
 * reading: no ids means no content.</p>
 */
@FunctionalInterface
public interface InstalledModIds {

    List<String> all();

    /** The stand-in used when nothing is registered: the process sees no mod content. */
    static InstalledModIds none() {
        return List::of;
    }
}