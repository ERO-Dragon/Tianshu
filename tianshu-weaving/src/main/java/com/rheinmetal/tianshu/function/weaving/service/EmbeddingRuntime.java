package com.rheinmetal.tianshu.function.weaving.service;

import java.util.List;

/**
 * The one live handle to the local embedding engine.
 *
 * <p>RAG storage, the {@code EMBED} primitive and the runtime snapshot all read the same object, so
 * there is no way for one of them to answer "vectors are available" while another throws. The engine
 * is bound later than the service is built (it loads on the model-load lane), which is why this is a
 * mutable holder rather than a constructor argument.</p>
 */
public final class EmbeddingRuntime implements EmbeddingService {
    private volatile EmbeddingService delegate;

    /** Binds the live engine, or clears the lane when {@code service} is null. */
    public void bind(EmbeddingService service) {
        this.delegate = service;
    }

    public boolean available() {
        return delegate != null;
    }

    @Override
    public float[] embed(String text) throws Exception {
        return requireDelegate().embed(text);
    }

    @Override
    public float[][] embed(List<String> texts) throws Exception {
        return requireDelegate().embed(texts);
    }

    @Override
    public int getEmbeddingDimension() {
        EmbeddingService current = delegate;
        return current == null ? 0 : Math.max(0, current.getEmbeddingDimension());
    }

    private EmbeddingService requireDelegate() {
        EmbeddingService current = delegate;
        if (current == null) {
            throw new IllegalStateException("EMBEDDING_NOT_READY");
        }
        return current;
    }

    /** Value used by the runtime snapshot when the lane is not bound. */
    public int safeDimension() {
        return available() ? getEmbeddingDimension() : -1;
    }
}
