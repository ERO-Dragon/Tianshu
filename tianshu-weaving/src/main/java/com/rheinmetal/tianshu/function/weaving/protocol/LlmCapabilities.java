package com.rheinmetal.tianshu.function.weaving.protocol;

/**
 * The capabilities the cloud-LLM module provides.
 *
 * <p>They live here rather than in api: a capability id is the producer's contract, and the kernel offers
 * only mechanism. A consumer that hard-depends on this module imports this class; an optional consumer uses
 * the id as a string in its bridge, exactly like any other cross-jar contract.</p>
 */
public final class LlmCapabilities {

    public static final String LLM_REQUEST = "LLM.REQUEST";
    public static final String LLM_CACHE_MANAGE = "LLM.CACHE_MANAGE";
    public static final String LLM_PRIMITIVE_QUERY = "LLM.PRIMITIVE_QUERY";

    private LlmCapabilities() {
    }
}
