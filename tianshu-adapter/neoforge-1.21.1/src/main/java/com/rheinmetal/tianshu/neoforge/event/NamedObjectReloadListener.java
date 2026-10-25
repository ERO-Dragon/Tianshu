package com.rheinmetal.tianshu.neoforge.event;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.assembly.KeywordTableReloadPort;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.NamedObjectIndex;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.util.Objects;
import java.util.function.Supplier;

public final class NamedObjectReloadListener extends SimplePreparableReloadListener<byte[]> {
    /**
     * The optional resource-pack override of the intent keyword table.
     *
     * <p>The table itself ships in the intake module, beside {@code IntentKeywordLoader}, which reads it on
     * first use; this listener only replaces it when a pack provides a file with this name. A missing
     * override is therefore the normal case and is not reported at all, while a read failure still is.</p>
     */
    private static final ResourceLocation KEYWORDS_ID = ResourceLocation.fromNamespaceAndPath("tianshu", "ir-intent-keywords.json");
    private final Runnable refreshSnapshot;
    private final LogSink logSink;

    public NamedObjectReloadListener(Runnable refreshSnapshot, LogSink logSink) {
        this.refreshSnapshot = refreshSnapshot == null ? () -> { } : refreshSnapshot;
        this.logSink = logSink == null ? LogSink.NOOP : logSink;
    }

    @Override
    protected byte[] prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        return readKeywords(resourceManager);
    }

    @Override
    protected void apply(byte[] keywords, ResourceManager resourceManager, ProfilerFiller profiler) {
        refreshSnapshot.run();
        // The module that owns the index decides when to rebuild it; an absent module means nobody to ask.
        TianshuRuntimePorts.find(NamedObjectIndex.class).ifPresent(index ->
                index.reloadAsync(
                        "client resource reload",
                        () -> reloadKeywords(keywords)
                ));
    }

    private byte[] readKeywords(ResourceManager resourceManager) {
        Resource resource = resourceManager.getResource(KEYWORDS_ID).orElse(null);
        if (resource == null) {
            // No pack overrides the table: the bundled copy in the intake module stays in force.
            return null;
        }
        try (InputStream input = resource.open()) {
            return input.readAllBytes();
        } catch (Exception e) {
            logSink.error("neoforge.ir.keywords.resource_read_failed id=" + KEYWORDS_ID, e);
            return null;
        }
    }

    private void reloadKeywords(byte[] keywords) {
        if (keywords == null || keywords.length == 0) {
            return;
        }
        try (InputStream input = new ByteArrayInputStream(keywords)) {
            TianshuRuntimePorts.find(KeywordTableReloadPort.class).orElseThrow().reload(input);
            logSink.info("neoforge.ir.keywords.reloaded");
        } catch (Exception e) {
            logSink.error("neoforge.ir.keywords.reload_failed", e);
        }
    }
}
