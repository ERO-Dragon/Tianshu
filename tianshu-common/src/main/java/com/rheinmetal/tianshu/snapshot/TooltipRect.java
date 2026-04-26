package com.rheinmetal.tianshu.snapshot;

public final class TooltipRect {

    public final int x;
    public final int y;
    public final int width;
    public final int height;

    public TooltipRect(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
}
