package com.adpilot.modules.advertising.converter;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.vo.CampaignVo;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for Amazon-synced-list inclusion gating performed by
 * {@link CampaignConverter#syncedToAmazon(CampaignEntity, boolean)} and the two-arg
 * {@link CampaignConverter#toVo(CampaignEntity, boolean)} projection.
 *
 * <p>Feature: advertising-workspace-rework, Property 29: Synced-list inclusion is gated by
 * effective creation and amazon_campaign_id.
 *
 * <p>Validates: Requirements 12.7, 12.8.
 *
 * <p>For any Campaign, it appears in the Amazon-synced campaign list <strong>iff</strong> it has a
 * non-empty {@code amazon_campaign_id} AND its creation Operation reached the {@code effective}
 * Sync_State; otherwise it appears only in the local-drafts view / with a local-source badge, and
 * {@code localSource} is the exact negation of {@code syncedToAmazon}. A Campaign whose
 * {@code origin} is {@code amazon_import} was ingested from Amazon and therefore already exists (is
 * effective) on the platform, so an Amazon id alone satisfies the "creation reached effective"
 * condition for it (Req 12.7 — visibility "by virtue of having sync_state = effective and a
 * non-empty amazon_campaign_id").
 */
class SyncedListInclusionPropertyTest {

    private static final String ORIGIN_AMAZON_IMPORT = "amazon_import";

    /**
     * Feature: advertising-workspace-rework, Property 29: Synced-list inclusion is gated by
     * effective creation and amazon_campaign_id.
     *
     * <p>Validates: Requirements 12.7, 12.8.
     *
     * <p>Across arbitrary {@code amazon_campaign_id} (null / blank / present), {@code origin}, and
     * {@code creationOperationEffective} combinations, both the pure predicate and the {@code toVo}
     * overload include the Campaign in the synced list exactly when it has a non-empty
     * {@code amazon_campaign_id} and a creation Operation that reached {@code effective} (an
     * {@code amazon_import} Campaign being treated as already effective). {@code localSource} is
     * always the exact negation of {@code syncedToAmazon}.
     */
    @Property(tries = 200)
    @Tag("pbt")
    @Label("Feature: advertising-workspace-rework, Property 29: Synced-list inclusion is gated by effective creation and amazon_campaign_id")
    void syncedListInclusionIsGatedByEffectiveCreationAndAmazonCampaignId(
            @ForAll("amazonCampaignIds") String amazonCampaignId,
            @ForAll("origins") String origin,
            @ForAll boolean creationOperationEffective) {

        CampaignEntity entity = baseCampaign()
                .origin(origin)
                .amazonCampaignId(amazonCampaignId)
                .build();

        // Independent re-statement of the gate from the requirement text, used as the oracle.
        boolean hasAmazonCampaignId = amazonCampaignId != null && !amazonCampaignId.isBlank();
        boolean amazonImport = ORIGIN_AMAZON_IMPORT.equalsIgnoreCase(origin);
        boolean creationEffective = amazonImport || creationOperationEffective;
        boolean expectedSynced = hasAmazonCampaignId && creationEffective;

        // 1) The pure predicate matches the gate.
        boolean predicate = CampaignConverter.syncedToAmazon(entity, creationOperationEffective);
        assertThat(predicate).isEqualTo(expectedSynced);

        // 2) The two-arg projection surfaces the same decision and the negation as localSource.
        CampaignVo vo = CampaignConverter.toVo(entity, creationOperationEffective);
        assertThat(vo.isSyncedToAmazon()).isEqualTo(expectedSynced);
        assertThat(vo.isLocalSource()).isEqualTo(!expectedSynced);

        // 3) localSource is ALWAYS the exact negation of syncedToAmazon (no third state).
        assertThat(vo.isLocalSource()).isNotEqualTo(vo.isSyncedToAmazon());

        // 4) A blank/null Amazon id can never be in the synced list, regardless of creation state.
        if (!hasAmazonCampaignId) {
            assertThat(predicate).isFalse();
            assertThat(vo.isSyncedToAmazon()).isFalse();
        }
    }

    // --- fixtures ----------------------------------------------------------

    /** A minimal valid Campaign with the id/storeId that {@code toVo} dereferences. */
    private static CampaignEntity.CampaignEntityBuilder baseCampaign() {
        return CampaignEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .name("c")
                .budget(BigDecimal.ZERO);
    }

    // --- generators --------------------------------------------------------

    /**
     * Arbitrary {@code amazon_campaign_id}: null, blank (empty / whitespace), or a present
     * non-blank id — covering the three branches of the non-empty check.
     */
    @Provide
    Arbitrary<String> amazonCampaignIds() {
        Arbitrary<String> nulls = Arbitraries.just(null);
        Arbitrary<String> blanks = Arbitraries.of("", " ", "   ", "\t");
        Arbitrary<String> present = Arbitraries.strings()
                .withCharRange('a', 'z')
                .numeric()
                .ofMinLength(1)
                .ofMaxLength(40)
                .filter(s -> !s.isBlank());
        return Arbitraries.oneOf(nulls, blanks, present);
    }

    /**
     * Arbitrary {@code origin}: {@code local}, {@code amazon_import} (incl. mixed case to exercise
     * the case-insensitive match), null, and arbitrary other strings.
     */
    @Provide
    Arbitrary<String> origins() {
        Arbitrary<String> known = Arbitraries.of(
                "local", "amazon_import", "AMAZON_IMPORT", "Amazon_Import", null);
        Arbitrary<String> arbitrary = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(0)
                .ofMaxLength(12);
        return Combinators.combine(
                        Arbitraries.integers().between(0, 1), known, arbitrary)
                .as((pick, k, a) -> pick == 0 ? k : a);
    }
}
