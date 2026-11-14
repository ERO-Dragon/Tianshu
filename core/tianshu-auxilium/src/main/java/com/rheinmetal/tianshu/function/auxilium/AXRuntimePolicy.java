package com.rheinmetal.tianshu.function.auxilium;

public record AXRuntimePolicy(
        long retrievalPrimitiveTimeoutMillis,
        long maintenancePrimitiveTimeoutMillis,
        long ragTimeoutMillis,
        long dynamicFactTimeoutMillis
) {
    private static final long DEFAULT_RETRIEVAL_PRIMITIVE_TIMEOUT_MILLIS = 1_000L;
    private static final long DEFAULT_MAINTENANCE_PRIMITIVE_TIMEOUT_MILLIS = 30_000L;
    private static final long DEFAULT_RAG_TIMEOUT_MILLIS = 2_000L;
    private static final long DEFAULT_DYNAMIC_FACT_TIMEOUT_MILLIS = 300L;

    public static AXRuntimePolicy defaults() {
        return new AXRuntimePolicy(
                DEFAULT_RETRIEVAL_PRIMITIVE_TIMEOUT_MILLIS,
                DEFAULT_MAINTENANCE_PRIMITIVE_TIMEOUT_MILLIS,
                DEFAULT_RAG_TIMEOUT_MILLIS,
                DEFAULT_DYNAMIC_FACT_TIMEOUT_MILLIS
        );
    }

    public AXRuntimePolicy {
        requirePositive(retrievalPrimitiveTimeoutMillis, "retrievalPrimitiveTimeoutMillis");
        requirePositive(maintenancePrimitiveTimeoutMillis, "maintenancePrimitiveTimeoutMillis");
        requirePositive(ragTimeoutMillis, "ragTimeoutMillis");
        requirePositive(dynamicFactTimeoutMillis, "dynamicFactTimeoutMillis");
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
