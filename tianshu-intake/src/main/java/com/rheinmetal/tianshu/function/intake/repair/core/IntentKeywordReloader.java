package com.rheinmetal.tianshu.function.intake.repair.core;

import com.rheinmetal.tianshu.api.assembly.KeywordTableReloadPort;

import java.io.InputStream;

/**
 * Intake's implementation of the keyword-table reload port.
 *
 * <p>The platform hands over the resource bytes; this adapter feeds them to the loader that owns the
 * table, so the platform never names {@link IntentKeywordLoader}.</p>
 */
public final class IntentKeywordReloader implements KeywordTableReloadPort {

    @Override
    public void reload(InputStream table) throws Exception {
        IntentKeywordLoader.reload(table);
    }
}
