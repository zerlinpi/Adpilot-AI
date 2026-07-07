package com.adpilot.modules.apisync.model;

/**
 * Opaque paging cursor for connector pulls. A {@code null} cursor (or
 * {@link #start()}) denotes the first page; connectors echo back the next
 * cursor in {@link ExternalPage#next()} until {@link ExternalPage#hasMore()}
 * is {@code false}.
 *
 * @param token platform-specific continuation token (page number, since-id,
 *              next-page URL, etc.); {@code null} for the first page
 */
public record PageCursor(String token) {

    /** Cursor representing the first page of a pull. */
    public static PageCursor start() {
        return new PageCursor(null);
    }

    public static PageCursor of(String token) {
        return new PageCursor(token);
    }

    public boolean isStart() {
        return token == null || token.isBlank();
    }
}
