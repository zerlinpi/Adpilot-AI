package com.adpilot.modules.advertising.hosting;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Property-based test for brand-word protection in the V3 Keyword Engine.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 30: Brand-word protection
 *
 * <p><b>Validates: Requirements 5.4, 22.3, 22.4</b>
 *
 * <p>Properties tested:
 * <ol>
 *   <li>Any search term that exactly matches a brand word (case-insensitive) in "exact" mode is always rejected with reason BRAND_PROTECTED</li>
 *   <li>Any search term containing a brand word as a substring (case-insensitive) in "contains" mode is always rejected</li>
 *   <li>A search term that does NOT match any brand word (in its configured mode) is always allowed</li>
 *   <li>The check is case-insensitive (upper/lower/mixed case of the same string always produces the same result)</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 30: Brand-word protection")
class BrandWordProtectionPropertyTest {

    private static final int MIN_ITERATIONS = 200;
    private static final UUID STORE_ID = UUID.randomUUID();

    // ── Service factory ─────────────────────────────────────────────────────────

    /**
     * Build a BrandWordProtectionServiceImpl with a mocked mapper returning the
     * given brand word list for our test store.
     */
    private BrandWordProtectionServiceImpl buildService(List<BrandWordEntity> brandWords) {
        BrandWordMapper mapper = mock(BrandWordMapper.class);
        when(mapper.selectByStoreId(STORE_ID)).thenReturn(brandWords);
        return new BrandWordProtectionServiceImpl(mapper, 300L);
    }

    private BrandWordEntity brandWord(String word, String matchType) {
        return BrandWordEntity.builder()
                .id(UUID.randomUUID())
                .storeId(STORE_ID)
                .word(word)
                .matchType(matchType)
                .build();
    }

    // ── Properties ──────────────────────────────────────────────────────────────

    /**
     * Property 1: Any search term that exactly matches a brand word (case-insensitive)
     * in "exact" mode is always rejected with reason BRAND_PROTECTED.
     *
     * <p>Validates: Requirements 5.4, 22.3
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Req 5.4/22.3: Exact match (case-insensitive) → always rejected with BRAND_PROTECTED")
    void exactMatchAlwaysRejected(
            @ForAll("brandWords") String brandWordText,
            @ForAll("caseVariants") CaseVariant caseVariant) {

        // The search term is the brand word in a different case variant
        String searchTerm = applyCase(brandWordText, caseVariant);

        BrandWordProtectionServiceImpl service = buildService(
                List.of(brandWord(brandWordText, "exact")));

        BrandProtectionResult result = service.checkNegativeCandidate(STORE_ID, searchTerm);

        assertThat(result.rejected()).isTrue();
        assertThat(result.reason()).isEqualTo(BrandProtectionResult.REASON_BRAND_PROTECTED);
        assertThat(result.matchedWord()).isEqualTo(brandWordText);
        assertThat(result.matchType()).isEqualTo("exact");
    }

    /**
     * Property 2: Any search term containing a brand word as a substring (case-insensitive)
     * in "contains" mode is always rejected.
     *
     * <p>Validates: Requirements 5.4, 22.3
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Req 5.4/22.3: Contains match (case-insensitive) → always rejected")
    void containsMatchAlwaysRejected(
            @ForAll("brandWords") String brandWordText,
            @ForAll("prefixes") String prefix,
            @ForAll("suffixes") String suffix,
            @ForAll("caseVariants") CaseVariant caseVariant) {

        // Build a search term that contains the brand word as a substring
        String embeddedBrandWord = applyCase(brandWordText, caseVariant);
        String searchTerm = prefix + embeddedBrandWord + suffix;

        BrandWordProtectionServiceImpl service = buildService(
                List.of(brandWord(brandWordText, "contains")));

        BrandProtectionResult result = service.checkNegativeCandidate(STORE_ID, searchTerm);

        assertThat(result.rejected()).isTrue();
        assertThat(result.reason()).isEqualTo(BrandProtectionResult.REASON_BRAND_PROTECTED);
        assertThat(result.matchedWord()).isEqualTo(brandWordText);
        assertThat(result.matchType()).isEqualTo("contains");
    }

    /**
     * Property 3: A search term that does NOT match any brand word (in its configured mode)
     * is always allowed.
     *
     * <p>Validates: Requirements 5.4, 22.4
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Req 5.4/22.4: Non-matching term → always allowed")
    void nonMatchingTermAlwaysAllowed(
            @ForAll("brandWords") String brandWordText,
            @ForAll("nonMatchingTerms") String nonMatchingTerm,
            @ForAll("matchTypes") String matchType) {

        // Ensure the non-matching term truly doesn't match
        String termLower = nonMatchingTerm.toLowerCase(Locale.ROOT);
        String wordLower = brandWordText.toLowerCase(Locale.ROOT);

        // Skip if the generated term accidentally matches
        if ("exact".equals(matchType) && termLower.equals(wordLower)) {
            return; // filter this trial
        }
        if ("contains".equals(matchType) && termLower.contains(wordLower)) {
            return; // filter this trial
        }

        BrandWordProtectionServiceImpl service = buildService(
                List.of(brandWord(brandWordText, matchType)));

        BrandProtectionResult result = service.checkNegativeCandidate(STORE_ID, nonMatchingTerm);

        assertThat(result.rejected()).isFalse();
        assertThat(result.reason()).isNull();
        assertThat(result.matchedWord()).isNull();
        assertThat(result.matchType()).isNull();
    }

    /**
     * Property 4: The check is case-insensitive — upper/lower/mixed case of the same
     * string always produces the same result.
     *
     * <p>Validates: Requirements 22.3, 22.4
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Req 22.3/22.4: Case-insensitive — all case variants yield same result")
    void caseInsensitiveConsistency(
            @ForAll("brandWords") String brandWordText,
            @ForAll("searchTerms") String searchTerm,
            @ForAll("matchTypes") String matchType) {

        BrandWordProtectionServiceImpl service = buildService(
                List.of(brandWord(brandWordText, matchType)));

        // Check the original term
        BrandProtectionResult resultOriginal = service.checkNegativeCandidate(STORE_ID, searchTerm);

        // Invalidate cache between calls to force re-evaluation with same data
        service.invalidateCache(STORE_ID);

        // Check the uppercase variant
        BrandProtectionResult resultUpper = service.checkNegativeCandidate(STORE_ID,
                searchTerm.toUpperCase(Locale.ROOT));

        service.invalidateCache(STORE_ID);

        // Check the lowercase variant
        BrandProtectionResult resultLower = service.checkNegativeCandidate(STORE_ID,
                searchTerm.toLowerCase(Locale.ROOT));

        // All case variants must produce the same rejected/allowed decision
        assertThat(resultOriginal.rejected())
                .as("original vs upper case should agree")
                .isEqualTo(resultUpper.rejected());
        assertThat(resultOriginal.rejected())
                .as("original vs lower case should agree")
                .isEqualTo(resultLower.rejected());
    }

    // ── Arbitraries ─────────────────────────────────────────────────────────────

    /** Brand words: non-empty alphabetic strings of 2-20 characters. */
    @Provide
    Arbitrary<String> brandWords() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(2)
                .ofMaxLength(20);
    }

    /** Search terms for the case-insensitivity property: non-blank mixed-case strings. */
    @Provide
    Arbitrary<String> searchTerms() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .ofMinLength(1)
                .ofMaxLength(30);
    }

    /**
     * Non-matching terms: strings that are unlikely to contain or equal the brand word.
     * Uses a different character range (digits + special) to virtually guarantee no match.
     */
    @Provide
    Arbitrary<String> nonMatchingTerms() {
        return Arbitraries.strings()
                .withCharRange('0', '9')
                .ofMinLength(1)
                .ofMaxLength(20);
    }

    /** Valid match types: "exact" or "contains". */
    @Provide
    Arbitrary<String> matchTypes() {
        return Arbitraries.of("exact", "contains");
    }

    /** Case variants for applying to a string. */
    @Provide
    Arbitrary<CaseVariant> caseVariants() {
        return Arbitraries.of(CaseVariant.values());
    }

    /** Prefixes for building substring-containing terms (may be empty). */
    @Provide
    Arbitrary<String> prefixes() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(0)
                .ofMaxLength(10);
    }

    /** Suffixes for building substring-containing terms (may be empty). */
    @Provide
    Arbitrary<String> suffixes() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(0)
                .ofMaxLength(10);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    enum CaseVariant {
        LOWER, UPPER, MIXED
    }

    private String applyCase(String text, CaseVariant variant) {
        return switch (variant) {
            case LOWER -> text.toLowerCase(Locale.ROOT);
            case UPPER -> text.toUpperCase(Locale.ROOT);
            case MIXED -> toMixedCase(text);
        };
    }

    /**
     * Convert to mixed case: alternate lower and upper case per character.
     */
    private String toMixedCase(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(i % 2 == 0
                    ? Character.toLowerCase(c)
                    : Character.toUpperCase(c));
        }
        return sb.toString();
    }
}
