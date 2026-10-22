package com.rheinmetal.tianshu.protocol.broker;

public final class BrokerSubmitResult {
    private static final BrokerSubmitResult ACCEPTED = new BrokerSubmitResult(true, false, "ACCEPTED", "");

    private final boolean accepted;
    private final boolean rejected;
    private final String code;
    private final String message;

    private BrokerSubmitResult(boolean accepted, boolean rejected, String code, String message) {
        this.accepted = accepted;
        this.rejected = rejected;
        this.code = code;
        this.message = message;
    }

    public static BrokerSubmitResult accept() {
        return ACCEPTED;
    }

    public static BrokerSubmitResult rejected(String code, String message) {
        return new BrokerSubmitResult(false, true, code, message);
    }

    public boolean accepted() { return accepted; }
    public boolean rejected() { return rejected; }
    public String code() { return code; }
    public String message() { return message; }
}
