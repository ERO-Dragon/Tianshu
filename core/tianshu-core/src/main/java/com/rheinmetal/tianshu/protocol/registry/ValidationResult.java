package com.rheinmetal.tianshu.protocol.registry;

public final class ValidationResult {
    private static final ValidationResult ACCEPT = new ValidationResult(true, "OK", "");

    private final boolean accepted;
    private final String code;
    private final String message;

    private ValidationResult(boolean accepted, String code, String message) {
        this.accepted = accepted;
        this.code = code;
        this.message = message;
    }

    public static ValidationResult accept() {
        return ACCEPT;
    }

    public static ValidationResult reject(String code, String message) {
        return new ValidationResult(false, code, message);
    }

    public boolean accepted() { return accepted; }
    public String code() { return code; }
    public String message() { return message; }
}
