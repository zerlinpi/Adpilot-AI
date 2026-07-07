package com.adpilot.modules.advertising.service;

import java.util.Arrays;
import java.util.Optional;

/**
 * The action chosen when harvesting a Search_Term (Req 13). Exactly one of
 * {@code add_exact}, {@code add_phrase}, {@code add_negative}, or
 * {@code watchlist}. Any other value is invalid and rejected (Req 13.7).
 */
public enum HarvestAction {

    /** Create an enabled exact-match Keyword in the resolved ad group (Req 13.3). */
    ADD_EXACT("add_exact"),

    /** Create an enabled phrase-match Keyword in the resolved ad group (Req 13.4). */
    ADD_PHRASE("add_phrase"),

    /** Create a Negative_Keyword (never a positive Keyword) for the target (Req 13.5). */
    ADD_NEGATIVE("add_negative"),

    /** Record the Search_Term as watched; create no Keyword or Negative_Keyword (Req 13.6). */
    WATCHLIST("watchlist");

    private final String value;

    HarvestAction(String value) {
        this.value = value;
    }

    /** The stable machine value as carried over the API. */
    public String value() {
        return value;
    }

    /** True when the action produces a positive Keyword. */
    public boolean producesKeyword() {
        return this == ADD_EXACT || this == ADD_PHRASE;
    }

    /**
     * Parse a raw action string into a {@link HarvestAction}, returning empty for
     * any unrecognised or blank value so the caller can reject it while naming the
     * invalid action (Req 13.7).
     */
    public static Optional<HarvestAction> fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim();
        return Arrays.stream(values())
                .filter(a -> a.value.equalsIgnoreCase(normalized))
                .findFirst();
    }
}
