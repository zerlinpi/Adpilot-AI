package com.adpilot.modules.advertising.hosting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BrandWordProtectionServiceImpl}.
 *
 * <p><b>Validates: Requirements 5.4, 22.3, 22.4</b></p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BrandWordProtectionServiceImpl — brand-word protection checks")
class BrandWordProtectionServiceImplTest {

    @Mock
    private BrandWordMapper brandWordMapper;

    private BrandWordProtectionServiceImpl service;

    private static final UUID STORE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // TTL of 300 seconds for cache
        service = new BrandWordProtectionServiceImpl(brandWordMapper, 300L);
    }

    @Nested
    @DisplayName("Exact match mode")
    class ExactMatch {

        @Test
        @DisplayName("rejects a term that exactly matches a brand word (case-insensitive)")
        void rejectsExactMatch() {
            BrandWordEntity brandWord = BrandWordEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(STORE_ID)
                    .word("Acme")
                    .matchType("exact")
                    .build();
            when(brandWordMapper.selectByStoreId(STORE_ID)).thenReturn(List.of(brandWord));

            BrandProtectionResult result = service.checkNegativeCandidate(STORE_ID, "acme");

            assertThat(result.rejected()).isTrue();
            assertThat(result.reason()).isEqualTo("BRAND_PROTECTED");
            assertThat(result.matchedWord()).isEqualTo("Acme");
            assertThat(result.matchType()).isEqualTo("exact");
        }

        @Test
        @DisplayName("rejects exact match regardless of case")
        void rejectsExactMatchUpperCase() {
            BrandWordEntity brandWord = BrandWordEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(STORE_ID)
                    .word("BrandX")
                    .matchType("exact")
                    .build();
            when(brandWordMapper.selectByStoreId(STORE_ID)).thenReturn(List.of(brandWord));

            BrandProtectionResult result = service.checkNegativeCandidate(STORE_ID, "BRANDX");

            assertThat(result.rejected()).isTrue();
            assertThat(result.reason()).isEqualTo("BRAND_PROTECTED");
            assertThat(result.matchedWord()).isEqualTo("BrandX");
        }

        @Test
        @DisplayName("allows a term that does not exactly match (partial is not exact)")
        void allowsPartialNoMatch() {
            BrandWordEntity brandWord = BrandWordEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(STORE_ID)
                    .word("Nike")
                    .matchType("exact")
                    .build();
            when(brandWordMapper.selectByStoreId(STORE_ID)).thenReturn(List.of(brandWord));

            BrandProtectionResult result = service.checkNegativeCandidate(STORE_ID, "nike shoes");

            assertThat(result.rejected()).isFalse();
            assertThat(result.reason()).isNull();
        }
    }

    @Nested
    @DisplayName("Contains match mode")
    class ContainsMatch {

        @Test
        @DisplayName("rejects a term containing the brand word as substring")
        void rejectsSubstringMatch() {
            BrandWordEntity brandWord = BrandWordEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(STORE_ID)
                    .word("Nike")
                    .matchType("contains")
                    .build();
            when(brandWordMapper.selectByStoreId(STORE_ID)).thenReturn(List.of(brandWord));

            BrandProtectionResult result = service.checkNegativeCandidate(STORE_ID, "cheap nike shoes");

            assertThat(result.rejected()).isTrue();
            assertThat(result.reason()).isEqualTo("BRAND_PROTECTED");
            assertThat(result.matchedWord()).isEqualTo("Nike");
            assertThat(result.matchType()).isEqualTo("contains");
        }

        @Test
        @DisplayName("rejects when the term equals the brand word (contains includes exact)")
        void rejectsExactAsContains() {
            BrandWordEntity brandWord = BrandWordEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(STORE_ID)
                    .word("Adidas")
                    .matchType("contains")
                    .build();
            when(brandWordMapper.selectByStoreId(STORE_ID)).thenReturn(List.of(brandWord));

            BrandProtectionResult result = service.checkNegativeCandidate(STORE_ID, "adidas");

            assertThat(result.rejected()).isTrue();
            assertThat(result.matchType()).isEqualTo("contains");
        }

        @Test
        @DisplayName("allows a term that does not contain the brand word")
        void allowsNoSubstring() {
            BrandWordEntity brandWord = BrandWordEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(STORE_ID)
                    .word("Apple")
                    .matchType("contains")
                    .build();
            when(brandWordMapper.selectByStoreId(STORE_ID)).thenReturn(List.of(brandWord));

            BrandProtectionResult result = service.checkNegativeCandidate(STORE_ID, "orange juice");

            assertThat(result.rejected()).isFalse();
        }
    }

    @Nested
    @DisplayName("Multiple brand words")
    class MultipleBrandWords {

        @Test
        @DisplayName("matches against the first matching brand word in the list")
        void matchesFirstBrandWord() {
            BrandWordEntity word1 = BrandWordEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(STORE_ID)
                    .word("Alpha")
                    .matchType("exact")
                    .build();
            BrandWordEntity word2 = BrandWordEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(STORE_ID)
                    .word("Beta")
                    .matchType("contains")
                    .build();
            when(brandWordMapper.selectByStoreId(STORE_ID)).thenReturn(List.of(word1, word2));

            BrandProtectionResult result = service.checkNegativeCandidate(STORE_ID, "beta testing");

            assertThat(result.rejected()).isTrue();
            assertThat(result.matchedWord()).isEqualTo("Beta");
            assertThat(result.matchType()).isEqualTo("contains");
        }

        @Test
        @DisplayName("allows term when no brand word matches")
        void allowsWhenNoneMatch() {
            BrandWordEntity word1 = BrandWordEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(STORE_ID)
                    .word("Alpha")
                    .matchType("exact")
                    .build();
            BrandWordEntity word2 = BrandWordEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(STORE_ID)
                    .word("Beta")
                    .matchType("exact")
                    .build();
            when(brandWordMapper.selectByStoreId(STORE_ID)).thenReturn(List.of(word1, word2));

            BrandProtectionResult result = service.checkNegativeCandidate(STORE_ID, "gamma");

            assertThat(result.rejected()).isFalse();
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("allows when store has no brand words")
        void allowsEmptyBrandList() {
            when(brandWordMapper.selectByStoreId(STORE_ID)).thenReturn(Collections.emptyList());

            BrandProtectionResult result = service.checkNegativeCandidate(STORE_ID, "anything");

            assertThat(result.rejected()).isFalse();
        }

        @Test
        @DisplayName("allows when search term is null")
        void allowsNullTerm() {
            BrandProtectionResult result = service.checkNegativeCandidate(STORE_ID, null);

            assertThat(result.rejected()).isFalse();
        }

        @Test
        @DisplayName("allows when search term is blank")
        void allowsBlankTerm() {
            BrandProtectionResult result = service.checkNegativeCandidate(STORE_ID, "   ");

            assertThat(result.rejected()).isFalse();
        }

        @Test
        @DisplayName("allows when store ID is null")
        void allowsNullStore() {
            BrandProtectionResult result = service.checkNegativeCandidate(null, "term");

            assertThat(result.rejected()).isFalse();
        }
    }

    @Nested
    @DisplayName("Caching behavior")
    class Caching {

        @Test
        @DisplayName("uses cached brand words on subsequent calls within TTL")
        void cacheHit() {
            BrandWordEntity brandWord = BrandWordEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(STORE_ID)
                    .word("CachedBrand")
                    .matchType("exact")
                    .build();
            when(brandWordMapper.selectByStoreId(STORE_ID)).thenReturn(List.of(brandWord));

            // First call loads from DB
            service.checkNegativeCandidate(STORE_ID, "cachedbrand");
            // Second call should use cache
            service.checkNegativeCandidate(STORE_ID, "cachedbrand");

            // Mapper should only be called once
            verify(brandWordMapper, times(1)).selectByStoreId(STORE_ID);
        }

        @Test
        @DisplayName("invalidateCache forces reload on next call")
        void invalidateForcesReload() {
            BrandWordEntity brandWord = BrandWordEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(STORE_ID)
                    .word("Brand")
                    .matchType("exact")
                    .build();
            when(brandWordMapper.selectByStoreId(STORE_ID)).thenReturn(List.of(brandWord));

            // First call loads
            service.checkNegativeCandidate(STORE_ID, "brand");
            // Invalidate
            service.invalidateCache(STORE_ID);
            // Next call should reload
            service.checkNegativeCandidate(STORE_ID, "brand");

            verify(brandWordMapper, times(2)).selectByStoreId(STORE_ID);
        }
    }
}
