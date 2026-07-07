package com.adpilot.modules.advertising.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.CampaignProductLinkEntity;
import com.adpilot.modules.advertising.mapper.AdvertisedProductReportMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.CampaignProductLinkMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.support.CampaignPerfAggRow;
import com.adpilot.modules.advertising.vo.ProductCampaignVo;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for product&harr;campaign association completeness in
 * {@link ProductAdServiceImpl#listProductCampaigns}.
 *
 * <p>Feature: multistore-ai-ads-operations, Property 11: 产品&harr;活动关联查询完备且不泄漏
 * (product&harr;campaign association query is complete and never leaks).
 *
 * <p>Validates: Requirements 2.1.
 *
 * <p>Property 11 (transcribed from the design's Correctness Properties section):
 * <em>For any product and a set of campaign/link data, querying a product's
 * associated campaigns returns exactly the campaigns associated via
 * CampaignProductLink (by parent ASIN or local product id) — neither missing an
 * associated campaign nor including an unassociated one.</em>
 *
 * <p>The service is driven against mocked mappers so the test fully controls
 * which campaigns are linked to the queried product and which are not. The link
 * mapper is modelled as a scope-filtered store: it returns exactly the links
 * whose {@code store_id} matches the queried store and whose {@code parent_asin}
 * or {@code product_id} matches the queried product (mirroring the service's
 * {@code WHERE store_id = ? AND (parent_asin = ? OR product_id = ?)} clause).
 * The campaign mapper returns the store's campaigns by id, and the performance
 * mapper returns no rows (metrics are irrelevant to the association property).
 * We then assert the returned campaign-id set equals the deliberately-built
 * associated set exactly.
 */
@Tag("pbt")
@Label("Feature: multistore-ai-ads-operations, Property 11: 产品↔活动关联查询完备且不泄漏")
class ProductCampaignAssociationCompletenessPropertyTest {

    private static final int MIN_ITERATIONS = 100;

    private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static final String QUERIED_ASIN = "B0QUERIED01";
    private static final String OTHER_ASIN = "B0OTHERASIN9";

    /**
     * Feature: multistore-ai-ads-operations, Property 11: 产品↔活动关联查询完备且不泄漏.
     *
     * <p>Validates: Requirements 2.1.
     *
     * <p>For any mix of associated and unassociated campaign links (plus decoy
     * links that match the queried product but belong to a different store), the
     * campaigns returned for the queried product are exactly the campaigns
     * associated with it — complete (no associated campaign missing) and tight
     * (no unassociated or cross-store campaign leaked).
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 11: listProductCampaigns returns exactly the campaigns associated with the queried product")
    void listProductCampaignsReturnsExactlyAssociatedCampaigns(@ForAll("scenarios") Scenario scenario) {
        CampaignProductLinkMapper linkMapper = mock(CampaignProductLinkMapper.class);
        CampaignMapper campaignMapper = mock(CampaignMapper.class);
        PerformanceDailyMapper performanceDailyMapper = mock(PerformanceDailyMapper.class);
        AdvertisedProductReportMapper advertisedProductReportMapper = mock(AdvertisedProductReportMapper.class);
        DataScopeService dataScopeService = mock(DataScopeService.class);

        // Model the link mapper as a scope-filtered store: it returns exactly the
        // links matching the queried store and product, exactly as the service's
        // WHERE store_id = ? AND (parent_asin = ? OR product_id = ?) would.
        when(linkMapper.selectList(any())).thenReturn(matchingLinks(scenario));

        // The campaign mapper answers selectBatchIds with the store's campaigns by id.
        when(campaignMapper.selectBatchIds(anyList())).thenAnswer(inv -> {
            List<?> ids = inv.getArgument(0);
            List<CampaignEntity> out = new ArrayList<>();
            for (Object id : ids) {
                CampaignEntity c = scenario.campaignById.get(id);
                if (c != null) {
                    out.add(c);
                }
            }
            return out;
        });

        // Performance metrics are irrelevant to the association property.
        when(performanceDailyMapper.aggregateByCampaign(anyString(), anyList()))
                .thenReturn(Collections.<CampaignPerfAggRow>emptyList());

        ProductAdServiceImpl service = new ProductAdServiceImpl(
                advertisedProductReportMapper, campaignMapper, linkMapper,
                performanceDailyMapper, dataScopeService);

        CurrentUser user = CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .email("scope@test.local")
                .roles(Set.of("USER"))
                .permissions(List.of("advertising:view"))
                .build();

        List<ProductCampaignVo> result;
        try (MockedStatic<SecurityUtils> security = org.mockito.Mockito.mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAuthenticated).thenReturn(true);
            security.when(SecurityUtils::getCurrentUser).thenReturn(user);
            result = service.listProductCampaigns(
                    STORE_ID.toString(), QUERIED_ASIN, scenario.queriedProductIdString());
        }

        Set<String> returnedCampaignIds = result.stream()
                .map(ProductCampaignVo::getCampaignId)
                .collect(Collectors.toSet());

        // Completeness + no-leak: the returned set equals the associated set exactly.
        assertThat(returnedCampaignIds).isEqualTo(scenario.expectedCampaignIds);

        // Defence-in-depth: no unassociated or cross-store campaign id ever leaks
        // (empty-safe intersection check — the forbidden set may be empty).
        Set<String> leaked = new java.util.HashSet<>(returnedCampaignIds);
        leaked.retainAll(scenario.forbiddenCampaignIds);
        assertThat(leaked).isEmpty();
    }

    /**
     * The links the service's scope-filtered query would return for the queried
     * store and product: {@code store_id == STORE_ID} and
     * {@code (parent_asin == QUERIED_ASIN || product_id == queriedProductId)}.
     */
    private static List<CampaignProductLinkEntity> matchingLinks(Scenario scenario) {
        return scenario.linkStore.stream()
                .filter(l -> STORE_ID.equals(l.getStoreId()))
                .filter(l -> QUERIED_ASIN.equals(l.getParentAsin())
                        || (scenario.queriedProductId != null
                        && scenario.queriedProductId.equals(l.getProductId())))
                .collect(Collectors.toList());
    }

    // -----------------------------------------------------------------------------------------
    // Scenario + generator
    // -----------------------------------------------------------------------------------------

    /** A product&harr;campaign association scenario. */
    static final class Scenario {
        /** Optional local product-id selector (null = query by ASIN only). */
        final UUID queriedProductId;
        /** All links present in the backing store (associated, unassociated, cross-store). */
        final List<CampaignProductLinkEntity> linkStore;
        /** Campaign id &rarr; entity for every campaign owned by the queried store. */
        final java.util.Map<UUID, CampaignEntity> campaignById;
        /** Campaign ids genuinely associated with the queried product (ground truth). */
        final Set<String> expectedCampaignIds;
        /** Campaign ids that must never be returned (unassociated + cross-store). */
        final Set<String> forbiddenCampaignIds;

        Scenario(UUID queriedProductId, List<CampaignProductLinkEntity> linkStore,
                 java.util.Map<UUID, CampaignEntity> campaignById,
                 Set<String> expectedCampaignIds, Set<String> forbiddenCampaignIds) {
            this.queriedProductId = queriedProductId;
            this.linkStore = linkStore;
            this.campaignById = campaignById;
            this.expectedCampaignIds = expectedCampaignIds;
            this.forbiddenCampaignIds = forbiddenCampaignIds;
        }

        String queriedProductIdString() {
            return queriedProductId != null ? queriedProductId.toString() : null;
        }
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        return Combinators.combine(
                        Arbitraries.integers().between(0, 4),   // associated campaigns
                        Arbitraries.integers().between(0, 4),   // unassociated campaigns
                        Arbitraries.integers().between(0, 3),   // cross-store decoy links
                        Arbitraries.of(true, false))            // also query by product id
                .as(this::buildScenario);
    }

    private Scenario buildScenario(int assocCount, int unassocCount, int decoyCount, boolean byProductId) {
        UUID queriedProductId = byProductId ? UUID.randomUUID() : null;

        java.util.Map<UUID, CampaignEntity> campaignById = new java.util.LinkedHashMap<>();
        List<CampaignProductLinkEntity> linkStore = new ArrayList<>();
        Set<String> expected = new java.util.HashSet<>();
        Set<String> forbidden = new java.util.HashSet<>();

        // Associated campaigns: each carries exactly one link that matches the queried product.
        for (int i = 0; i < assocCount; i++) {
            UUID campaignId = UUID.randomUUID();
            campaignById.put(campaignId, campaign(campaignId, "assoc-" + i));
            expected.add(campaignId.toString());

            boolean matchByProductId = byProductId && (i % 2 == 0);
            if (matchByProductId) {
                // Matches via product_id only (parent_asin is a different ASIN).
                linkStore.add(link(STORE_ID, campaignId, OTHER_ASIN, queriedProductId));
            } else {
                // Matches via parent_asin.
                linkStore.add(link(STORE_ID, campaignId, QUERIED_ASIN, UUID.randomUUID()));
            }
        }

        // Unassociated campaigns: links in the same store but for a different product.
        for (int i = 0; i < unassocCount; i++) {
            UUID campaignId = UUID.randomUUID();
            campaignById.put(campaignId, campaign(campaignId, "unassoc-" + i));
            forbidden.add(campaignId.toString());
            linkStore.add(link(STORE_ID, campaignId, OTHER_ASIN, UUID.randomUUID()));
        }

        // Cross-store decoy links: match the queried product but belong to another store,
        // so the store predicate must exclude them (never leak across stores).
        for (int i = 0; i < decoyCount; i++) {
            UUID campaignId = UUID.randomUUID();
            forbidden.add(campaignId.toString());
            linkStore.add(link(OTHER_STORE_ID, campaignId, QUERIED_ASIN, queriedProductId));
        }

        Collections.shuffle(linkStore);
        return new Scenario(queriedProductId, linkStore, campaignById, expected, forbidden);
    }

    private static CampaignEntity campaign(UUID id, String name) {
        return CampaignEntity.builder()
                .id(id)
                .storeId(STORE_ID)
                .name(name)
                .status("enabled")
                .build();
    }

    private static CampaignProductLinkEntity link(UUID storeId, UUID campaignId,
                                                  String parentAsin, UUID productId) {
        return CampaignProductLinkEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .campaignId(campaignId)
                .parentAsin(parentAsin)
                .productId(productId)
                .build();
    }
}
