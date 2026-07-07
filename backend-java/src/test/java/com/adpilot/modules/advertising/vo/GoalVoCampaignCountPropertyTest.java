package com.adpilot.modules.advertising.vo;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the derived {@code campaignCount} of {@link GoalVo}.
 *
 * <p>Feature: advertising-workspace-rework, Property 33: GoalVo campaignCount equals its campaigns
 * collection length.
 *
 * <p>Validates: Requirements 14.4.
 *
 * <p>{@code GoalVo.campaignCount} is not stored independently; it is a derived getter that returns
 * {@code campaigns.size()} (treating a {@code null} collection as zero). This property asserts that,
 * for arbitrary populated {@code GoalVo} instances, {@code getCampaignCount()} always equals the
 * length of the {@code campaigns} collection — so the reported count can never drift from the
 * collection it describes — including after the collection is mutated/replaced post-construction.
 */
class GoalVoCampaignCountPropertyTest {

    /**
     * Feature: advertising-workspace-rework, Property 33: GoalVo campaignCount equals its campaigns
     * collection length.
     *
     * <p>Validates: Requirements 14.4.
     *
     * <p>For an arbitrary number of associated campaigns (including zero and the {@code null}
     * collection case), {@code getCampaignCount()} equals the campaigns collection length, both as
     * built and after the collection is replaced via the setter.
     */
    @Property(tries = 200)
    @Tag("pbt")
    @Label("Feature: advertising-workspace-rework, Property 33: GoalVo campaignCount equals its campaigns collection length")
    void campaignCountAlwaysEqualsCampaignsCollectionLength(
            @ForAll("campaignLists") List<CampaignVo> campaigns) {

        // 1) campaignCount derived at build time equals the supplied collection's length
        //    (a null collection is reported as zero).
        GoalVo goal = GoalVo.builder()
                .id(UUID.randomUUID().toString())
                .storeId(UUID.randomUUID().toString())
                .name("goal")
                .campaigns(campaigns)
                .build();

        int expected = campaigns == null ? 0 : campaigns.size();
        assertThat(goal.getCampaignCount()).isEqualTo(expected);
        if (campaigns != null) {
            assertThat(goal.getCampaignCount()).isEqualTo(goal.getCampaigns().size());
        }

        // 2) The count tracks the collection after replacement: it is never an independently
        //    stored value that can drift from the campaigns it describes.
        List<CampaignVo> replacement = campaigns == null ? List.of() : campaigns.subList(0, campaigns.size() / 2);
        goal.setCampaigns(replacement);
        assertThat(goal.getCampaignCount()).isEqualTo(replacement.size());
        assertThat(goal.getCampaignCount()).isEqualTo(goal.getCampaigns().size());

        // 3) Setting the collection to null reports zero (the documented null-safe behaviour).
        goal.setCampaigns(null);
        assertThat(goal.getCampaignCount()).isZero();
    }

    // --- generators --------------------------------------------------------

    /**
     * Arbitrary campaigns collections: occasionally {@code null}, otherwise a list of 0..20 minimal
     * {@link CampaignVo} instances. The element contents are irrelevant to the count, so only an id
     * is populated to keep generation cheap while still varying the length across the input space.
     */
    @Provide
    Arbitrary<List<CampaignVo>> campaignLists() {
        // A non-exhaustible element arbitrary (random ids) so jqwik runs the full random
        // sample of `tries` rather than collapsing to a small exhaustive enumeration.
        Arbitrary<CampaignVo> campaign = Arbitraries.strings()
                .withCharRange('a', 'z')
                .numeric()
                .ofMinLength(1)
                .ofMaxLength(36)
                .map(id -> CampaignVo.builder().id(id).build());
        Arbitrary<List<CampaignVo>> lists = campaign.list().ofMinSize(0).ofMaxSize(20);
        // Include the null-collection case so the null-safe derivation is exercised.
        return Arbitraries.oneOf(lists, Arbitraries.just(null));
    }
}
