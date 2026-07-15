package com.rheinmetal.tianshu.client.gui.settings.layout;

public record ScrollState(int offset, int contentHeight, int viewportHeight) {
    public int maxOffset() {
        return Math.max(0, contentHeight - viewportHeight);
    }

    public ScrollState withOffset(int offset) {
        return new ScrollState(Math.max(0, Math.min(offset, maxOffset())), contentHeight, viewportHeight);
    }

    public ScrollState withMetrics(int contentHeight, int viewportHeight) {
        return new ScrollState(offset, contentHeight, viewportHeight).withOffset(offset);
    }

    public boolean canScroll() {
        return contentHeight > viewportHeight;
    }
}


