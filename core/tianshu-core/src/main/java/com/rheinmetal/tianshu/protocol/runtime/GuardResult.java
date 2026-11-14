package com.rheinmetal.tianshu.protocol.runtime;

public final class GuardResult {
    private static final GuardResult ACCEPT = new GuardResult(true, "OK", "");

    private final boolean accepted;
    private final String code;
    private final String message;

    private GuardResult(boolean accepted, String code, String message) {
        this.accepted = accepted;
        this.code = code;
        this.message = message;
    }

    public static GuardResult accept() {
        return ACCEPT;
    }

    public static GuardResult reject(String code, String message) {
        return new GuardResult(false, code, message);
    }

    public boolean accepted() { return accepted; }
    public String code() { return code; }
    public String message() { return message; }
}
