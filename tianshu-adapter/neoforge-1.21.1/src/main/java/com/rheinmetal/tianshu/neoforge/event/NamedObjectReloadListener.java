package com.rheinmetal.tianshu.neoforge.event;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.function.intake.repair.core.IntentKeywordLoader;
import com.rheinmetal.tianshu.function.intake.index.ClientNamedObjectIndexManager;
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
    private final Supplier<ClientNamedObjectIndexManager> indexManagerSupplier;
    private final Runnable refreshSnapshot;
    private final LogSink logSink;

    public NamedObjectReloadListener(Supplier<ClientNamedObjectIndexManager> indexManagerSupplier, Runnable refreshSnapshot, LogSink logSink) {
        this.indexManagerSupplier = Objects.requireNonNull(indexManagerSupplier, "indexManagerSupplier");
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
        ClientNamedObjectIndexManager indexManager = indexManagerSupplier.get();
        indexManager.reloadAsync(
                "client resource reload",
                () -> reloadKeywords(keywords)
        );
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
            IntentKeywordLoader.reload(input);
            logSink.info("neoforge.ir.keywords.reloaded");
        } catch (Exception e) {
            logSink.error("neoforge.ir.keywords.reload_failed", e);
        }
    }
}
