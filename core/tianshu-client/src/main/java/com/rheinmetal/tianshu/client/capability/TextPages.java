package com.rheinmetal.tianshu.client.capability;

/** Splits long capability text into fixed-size pages and appends the navigation footer. */
public final class TextPages {

    public static final int DEFAULT_PAGE_CHARS = 6_000;

    private TextPages() {
    }

    public static String page(String text, int page, int pageChars) {
        if (page < 1) {
            throw new IllegalArgumentException("page starts at 1");
        }
        int pages = Math.max(1, (text.length() + pageChars - 1) / pageChars);
        if (page > pages) {
            throw new IllegalArgumentException("page " + page + " does not exist; the text has " + pages);
        }
        int start = (page - 1) * pageChars;
        int end = Math.min(text.length(), start + pageChars);
        String slice = text.substring(start, end);
        if (pages == 1) {
            return slice;
        }
        return slice + "\n\n[page " + page + "/" + pages
                + (page < pages ? " — next: page=" + (page + 1) : " — end") + "]";
    }
}
