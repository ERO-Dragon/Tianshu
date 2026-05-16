package com.rheinmetal.tianshu.integration;

public final class TianshuIntegrationAccess {
    private static volatile TianshuIntegrationApi api;

    private TianshuIntegrationAccess() {
    }

    public static boolean isAvailable() {
        return api != null;
    }

    public static TianshuIntegrationApi require() {
        TianshuIntegrationApi current = api;
        if (current == null) {
            throw new IllegalStateException("Tianshu integration API is not available");
        }
        return current;
    }

    public static TianshuIntegrationApi currentOrNull() {
        return api;
    }

    public static void publish(TianshuIntegrationApi integrationApi) {
        api = integrationApi;
    }

    public static void clear(TianshuIntegrationApi integrationApi) {
        if (api == integrationApi) {
            api = null;
        }
    }
}
