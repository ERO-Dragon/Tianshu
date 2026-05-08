package com.rheinmetal.tianshu.function.junk;

public final class JunkItemIdPolicy {
    private JunkItemIdPolicy() {
    }

    public static boolean isValidItemId(String itemId) {
        return itemId != null && !itemId.isBlank() && itemId.contains(":") && itemId.length() <= 128;
    }

    public static boolean canPersistAsJunk(String itemId) {
        return isValidItemId(itemId);
    }
}
