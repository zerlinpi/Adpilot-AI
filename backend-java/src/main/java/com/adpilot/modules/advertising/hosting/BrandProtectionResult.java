package com.adpilot.modules.advertising.hosting;

/**
 * Result of checking a negative-keyword candidate against the store's brand word list.
 *
 * <p>When {@code rejected} is true, the proposed negative keyword must not be added
 * because it matches a brand word. The {@code reason} is always {@code BRAND_PROTECTED}
 * for rejected results, and the {@code matchedWord} and {@code matchType} identify
 * which brand word triggered the rejection.</p>
 *
 * <p>Validates: Requirements 5.4, 22.3, 22.4.</p>
 *
 * @param rejected    true if the candidate is brand-protected and must not be added
 * @param reason      the rejection reason (always "BRAND_PROTECTED" when rejected, null otherwise)
 * @param matchedWord the brand word that matched (null if not rejected)
 * @param matchType   the match type that triggered ("exact" or "contains", null if not rejected)
 */
public record BrandProtectionResult(
        boolean rejected,
        String reason,
        String matchedWord,
        String matchType
) {

    /** Rejection reason constant for brand-protected terms. */
    public static final String REASON_BRAND_PROTECTED = "BRAND_PROTECTED";

    /**
     * Create a result indicating the term is brand-protected and must be rejected.
     *
     * @param matchedWord the brand word that matched
     * @param matchType   the match type ("exact" or "contains")
     * @return a rejected result
     */
    public static BrandProtectionResult rejected(String matchedWord, String matchType) {
        return new BrandProtectionResult(true, REASON_BRAND_PROTECTED, matchedWord, matchType);
    }

    /**
     * Create a result indicating the term is allowed (not brand-protected).
     *
     * @return an allowed result
     */
    public static BrandProtectionResult allowed() {
        return new BrandProtectionResult(false, null, null, null);
    }
}
