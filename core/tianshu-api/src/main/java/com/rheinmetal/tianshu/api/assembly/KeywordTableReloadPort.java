package com.rheinmetal.tianshu.api.assembly;

import java.io.InputStream;

/**
 * Reloads the voice-trigger keyword table from a resource the platform read.
 *
 * <p>The platform knows how to open a resource pack entry; the module knows how to parse and index the
 * table. This port is the boundary between the two.</p>
 */
@FunctionalInterface
public interface KeywordTableReloadPort {

    void reload(InputStream table) throws Exception;
}
