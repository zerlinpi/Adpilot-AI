package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeServiceImpl;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.CampaignProductLinkEntity;
import com.adpilot.modules.advertising.mapper.AdvertisedProductReportMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.CampaignProductLinkMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.support.CampaignPerfAggRow;
import com.adpilot.modules.advertising.vo.ProductCampaignVo;
import com.adpilot.modules.feishu.client.FeishuApiClient;
import com.adpilot.modules.feishu.entity.FeishuIntegrationEntity;
import com.adpilot.modules.feishu.mapper.FeishuActionRequestMapper;
import com.adpilot.modules.feishu.mapper.FeishuChatBindingMapper;
import com.adpilot.modules.feishu.mapper.FeishuIntegrationMapper;
import com.adpilot.modules.feishu.mapper.FeishuMessageLogMapper;
import com.adpilot.modules.feishu.mapper.FeishuNotificationRuleMapper;
import com.adpilot.modules.feishu.service.impl.FeishuServiceImpl;
import com.adpilot.modules.feishu.vo.FeishuIntegrationVo;
import com.adpilot.modules.store.entity.UserStoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.mapper.UserStoreMapper;
import com.adpilot.modules.user.entity.DataScope;
import com.adpilot.modules.user.entity.UserRole;
import com.adpilot.modules.user.mapper.DataScopeMapper;
import com.adpilot.modules.user.mapper.UserRoleMapper;
import com.adpilot.common.utils.CryptoUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Property-based test for store-scope read isolation across the store-scoped read
 * paths that route through {@link com.adpilot.common.security.DataScopeService#applyScope}:
 * product-ad / performance reads ({@link ProductAdServiceImpl#listProductCampaigns})
 * and Feishu integration view/manage reads ({@link FeishuServiceImpl#listIntegrations}).
 *
 * <p>Feature: multistore-ai-ads-operations, Property 8: 店铺范围读取隔离
 *
 * <p>Validates: Requirements 2.7, 6.3, 6.4, 7.6.
 *
 * <p>Property 8 (transcribed from the design's Correctness Properties section):
 * <em>For any store-scoped data read (product-ad &amp; performance data, store
 * list / switch candidates, Feishu integration view / manage), for any
 * non-super-admin account, the returned set contains only stores within its
 * Store_Group_Scope (and matching the current Nav_Block platform family), never
 * any other store-group's data.</em>
 *
 * <p>These tests follow the {@code TableViewIsolationPropertyTest} pattern: a
 * <b>real</b> {@link DataScopeServiceImpl} (driven by mocked RBAC mappers so it
 * resolves an {@code ASSIGNED_STORE} scope confined to a known set of store ids)
 * is wired into each read service, and the data mapper is modelled as a
 * scope-filtered store — it returns only the rows whose {@code store_id} appears
 * in the predicate the service actually issued. Each property then asserts
 * <b>both</b>:
 * <ul>
 *   <li>the returned set is isolated (only in-scope stores, never another
 *       store's data); and</li>
 *   <li>the issued query predicate carries the account's current Store_Group_Scope
 *       and never an out-of-scope store id.</li>
 * </ul>
 * If {@code applyScope} failed to contribute its predicate, the modelled mapper
 * would no longer return the in-scope rows and the completeness assertion would
 * fail — so the test catches a dropped scope predicate, not just a leak.
 */
@Tag("pbt")
@Label("Feature: multistore-ai-ads-operations, Property 8: 店铺范围读取隔离")
class StoreScopeReadIsolationPropertyTest {

    private static final int MIN_ITERATIONS = 100;

    private static final String QUERIED_ASIN = "B0QUERIED01";
    private static final String OTHER_ASIN = "B0OTHERASN1";

    /**
     * Feature: multistore-ai-ads-operations, Property 8: 店铺范围读取隔离
     *
     * <p>Validates: Requirements 2.7, 6.3, 6.4, 7.6.
     *
     * <p>Product-ad read: {@code listProductCampaigns} for an in-scope store
     * returns only campaigns associated through that store, never campaigns whose
     * links live in an out-of-scope store (even when those links match the queried
     * product), and the link query carries the account's Store_Group_Scope
     * predicate but not the out-of-scope store id.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 8: listProductCampaigns is confined to the account's Store_Group_Scope")
    void productAdReadIsConfinedToStoreGroupScope(
            @ForAll @IntRange(min = 0, max = 4) int associatedCount,
            @ForAll @IntRange(min = 0, max = 3) int decoyCount,
            @ForAll @IntRange(min = 0, max = 2) int extraScopeStores,
            @ForAll boolean byProductId) {

        UUID userId = UUID.randomUUID();
        UUID queriedStore = UUID.randomUUID();
        UUID outOfScopeStore = UUID.randomUUID();
        UUID queriedProductId = byProductId ? UUID.randomUUID() : null;

        // The account's Store_Group_Scope: the queried store plus optional siblings.
        Set<UUID> inScope = new LinkedHashSet<>();
        inScope.add(queriedStore);
        for (int i = 0; i < extraScopeStores; i++) {
            inScope.add(UUID.randomUUID());
        }

        List<CampaignProductLinkEntity> backingLinks = new ArrayList<>();
        Map<UUID, CampaignEntity> campaignById = new LinkedHashMap<>();
        Set<String> expectedCampaignIds = new HashSet<>();
        Set<String> forbiddenCampaignIds = new HashSet<>();

        // In-scope associations: one matching link per campaign, in the queried store.
        for (int i = 0; i < associatedCount; i++) {
            UUID campaignId = UUID.randomUUID();
            campaignById.put(campaignId, campaign(campaignId, queriedStore, "assoc-" + i));
            expectedCampaignIds.add(campaignId.toString());
            if (byProductId && i % 2 == 0) {
                backingLinks.add(link(queriedStore, campaignId, OTHER_ASIN, queriedProductId));
            } else {
                backingLinks.add(link(queriedStore, campaignId, QUERIED_ASIN, UUID.randomUUID()));
            }
        }

        // Out-of-scope decoys: links matching the queried product but in another store.
        for (int i = 0; i < decoyCount; i++) {
            UUID campaignId = UUID.randomUUID();
            forbiddenCampaignIds.add(campaignId.toString());
            backingLinks.add(link(outOfScopeStore, campaignId, QUERIED_ASIN, queriedProductId));
            campaignById.put(campaignId, campaign(campaignId, outOfScopeStore, "decoy-" + i));
        }
        Collections.shuffle(backingLinks);

        CampaignProductLinkMapper linkMapper = mock(CampaignProductLinkMapper.class);
        CampaignMapper campaignMapper = mock(CampaignMapper.class);
        PerformanceDailyMapper performanceDailyMapper = mock(PerformanceDailyMapper.class);
        AdvertisedProductReportMapper advertisedProductReportMapper = mock(AdvertisedProductReportMapper.class);
        DataScopeServiceImpl dataScopeService = scopedDataScopeService(userId, inScope);

        final QueryWrapper<?>[] capturedWrapper = new QueryWrapper<?>[1];

        // Model the link mapper as a scope-filtered store: it returns exactly the
        // links whose store_id appears in the predicate the service issued
        // (WHERE store_id = ? AND store_id IN (<scope>)) and that match the product.
        when(linkMapper.selectList(any())).thenAnswer(invocation -> {
            QueryWrapper<CampaignProductLinkEntity> wrapper = invocation.getArgument(0);
            capturedWrapper[0] = wrapper;
            Set<String> scopedStoreIds = uuidValuesIn(wrapper);
            return backingLinks.stream()
                    .filter(l -> l.getStoreId() != null && scopedStoreIds.contains(l.getStoreId().toString()))
                    .filter(l -> QUERIED_ASIN.equals(l.getParentAsin())
                            || (queriedProductId != null && queriedProductId.equals(l.getProductId())))
                    .collect(Collectors.toList());
        });
        when(campaignMapper.selectBatchIds(anyList())).thenAnswer(invocation -> {
            List<?> ids = invocation.getArgument(0);
            List<CampaignEntity> out = new ArrayList<>();
            for (Object id : ids) {
                CampaignEntity c = campaignById.get(id);
                if (c != null) {
                    out.add(c);
                }
            }
            return out;
        });
        when(performanceDailyMapper.aggregateByCampaign(anyString(), anyList()))
                .thenReturn(Collections.<CampaignPerfAggRow>emptyList());

        ProductAdServiceImpl service = new ProductAdServiceImpl(
                advertisedProductReportMapper, campaignMapper, linkMapper,
                performanceDailyMapper, dataScopeService);

        List<ProductCampaignVo> result;
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAuthenticated).thenReturn(true);
            security.when(SecurityUtils::getCurrentUser).thenReturn(nonSuperAdmin(userId));
            result = service.listProductCampaigns(
                    queriedStore.toString(), QUERIED_ASIN,
                    queriedProductId != null ? queriedProductId.toString() : null);
        }

        Set<String> returned = result.stream()
                .map(ProductCampaignVo::getCampaignId)
                .collect(Collectors.toSet());

        // Isolation + completeness: exactly the in-scope associations, no decoys.
        assertThat(returned).isEqualTo(expectedCampaignIds);
        Set<String> leaked = new HashSet<>(returned);
        leaked.retainAll(forbiddenCampaignIds);
        assertThat(leaked).isEmpty();

        // The issued predicate carries the account's whole Store_Group_Scope and
        // never the out-of-scope store id.
        Set<String> predicateIds = uuidValuesIn(capturedWrapper[0]);
        assertThat(predicateIds).containsAll(asStrings(inScope));
        assertThat(predicateIds).doesNotContain(outOfScopeStore.toString());
    }

    /**
     * Feature: multistore-ai-ads-operations, Property 8: 店铺范围读取隔离
     *
     * <p>Validates: Requirements 2.7, 6.3, 6.4, 7.6.
     *
     * <p>Feishu integration view / manage read: {@code listIntegrations} returns
     * only integrations whose store falls within the account's Store_Group_Scope,
     * never an integration bound to an out-of-scope store, and the query carries
     * the account's scope predicate but not the out-of-scope store id (Req 7.6).
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 8: listIntegrations is confined to the account's Store_Group_Scope")
    void feishuListIntegrationsIsConfinedToStoreGroupScope(
            @ForAll @IntRange(min = 1, max = 3) int inScopeWithIntegration,
            @ForAll @IntRange(min = 0, max = 3) int outOfScopeIntegrations,
            @ForAll @IntRange(min = 0, max = 2) int emptyScopeStores) {

        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID outOfScopeStore = UUID.randomUUID();

        Set<UUID> inScope = new LinkedHashSet<>();
        List<FeishuIntegrationEntity> backing = new ArrayList<>();
        Set<String> expectedStoreIds = new HashSet<>();

        // In-scope stores that each carry an integration.
        for (int i = 0; i < inScopeWithIntegration; i++) {
            UUID storeId = UUID.randomUUID();
            inScope.add(storeId);
            backing.add(integration(orgId, storeId));
            expectedStoreIds.add(storeId.toString());
        }
        // In-scope stores with no integration (must not break completeness).
        for (int i = 0; i < emptyScopeStores; i++) {
            inScope.add(UUID.randomUUID());
        }
        // Out-of-scope integrations in the same org (must never leak).
        for (int i = 0; i < outOfScopeIntegrations; i++) {
            backing.add(integration(orgId, outOfScopeStore));
        }
        Collections.shuffle(backing);

        FeishuIntegrationMapper integrationMapper = mock(FeishuIntegrationMapper.class);
        DataScopeServiceImpl dataScopeService = scopedDataScopeService(userId, inScope);
        FeishuServiceImpl service = new FeishuServiceImpl(
                integrationMapper,
                mock(FeishuMessageLogMapper.class),
                mock(FeishuChatBindingMapper.class),
                mock(FeishuNotificationRuleMapper.class),
                mock(FeishuActionRequestMapper.class),
                mock(FeishuApiClient.class),
                mock(CryptoUtil.class),
                mock(ObjectMapper.class),
                mock(StoreMapper.class),
                dataScopeService,
                new com.adpilot.modules.feishu.support.FeishuWebhookValidator(
                        "open.feishu.cn,open.larksuite.com"),
                new com.adpilot.common.resilience.CircuitBreaker(false, 5, 30),
                mock(com.adpilot.modules.audit.service.AuditLogService.class));

        final QueryWrapper<?>[] capturedWrapper = new QueryWrapper<?>[1];

        // Model the integration mapper as a scope-filtered store keyed off the
        // predicate the service issued (org_id = ? AND store_id IN (<scope>)).
        when(integrationMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<FeishuIntegrationEntity> page = invocation.getArgument(0);
            QueryWrapper<FeishuIntegrationEntity> wrapper = invocation.getArgument(1);
            capturedWrapper[0] = wrapper;
            Set<String> scopedStoreIds = uuidValuesIn(wrapper);
            List<FeishuIntegrationEntity> filtered = backing.stream()
                    .filter(e -> orgId.equals(e.getOrgId()))
                    .filter(e -> e.getStoreId() != null && scopedStoreIds.contains(e.getStoreId().toString()))
                    .collect(Collectors.toList());
            page.setRecords(filtered);
            page.setTotal(filtered.size());
            return page;
        });

        PageResponse<FeishuIntegrationVo> response;
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentOrgId).thenReturn(orgId.toString());
            security.when(SecurityUtils::isAuthenticated).thenReturn(true);
            security.when(SecurityUtils::getCurrentUser).thenReturn(nonSuperAdmin(userId));
            response = service.listIntegrations(1, 50);
        }

        Set<String> returnedStoreIds = response.getItems().stream()
                .map(FeishuIntegrationVo::getStoreId)
                .collect(Collectors.toSet());

        // Isolation + completeness: exactly the in-scope integrations, no leaks.
        assertThat(returnedStoreIds).isEqualTo(expectedStoreIds);
        assertThat(returnedStoreIds).doesNotContain(outOfScopeStore.toString());

        // The issued predicate carries the account's whole Store_Group_Scope and
        // never the out-of-scope store id.
        Set<String> predicateIds = uuidValuesIn(capturedWrapper[0]);
        assertThat(predicateIds).containsAll(asStrings(inScope));
        assertThat(predicateIds).doesNotContain(outOfScopeStore.toString());
    }

    // -----------------------------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Build a real {@link DataScopeServiceImpl} whose RBAC mappers are mocked so it
     * resolves an {@code ASSIGNED_STORE} effective scope confined to {@code inScope}
     * for the given non-super-admin user. The store-group store mapper is unused for
     * an assigned-store scope, so a bare mock suffices.
     */
    private static DataScopeServiceImpl scopedDataScopeService(UUID userId, Set<UUID> inScope) {
        DataScopeMapper dataScopeMapper = mock(DataScopeMapper.class);
        UserRoleMapper userRoleMapper = mock(UserRoleMapper.class);
        UserStoreMapper userStoreMapper = mock(UserStoreMapper.class);
        StoreMapper storeMapper = mock(StoreMapper.class);

        UUID roleId = UUID.randomUUID();
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                UserRole.builder().id(UUID.randomUUID()).userId(userId).roleId(roleId).build()));
        when(dataScopeMapper.selectList(any())).thenReturn(List.of(
                DataScope.builder().id(UUID.randomUUID()).roleId(roleId).scopeType("assigned_store").build()));
        when(userStoreMapper.selectList(any())).thenReturn(
                inScope.stream()
                        .map(sid -> UserStoreEntity.builder().id(UUID.randomUUID()).userId(userId).storeId(sid).build())
                        .collect(Collectors.toList()));

        return new DataScopeServiceImpl(dataScopeMapper, userRoleMapper, userStoreMapper, storeMapper);
    }

    /** A non-super-admin principal (resolves to a non-bypassing data scope). */
    private static CurrentUser nonSuperAdmin(UUID userId) {
        return CurrentUser.builder()
                .userId(userId.toString())
                .email("scope@test.local")
                .roles(Set.of("USER"))
                .permissions(List.of("advertising:view", "feishu:view"))
                .build();
    }

    /**
     * Collect every bound parameter value on the wrapper that is a UUID (a store id
     * from the {@code eq}/{@code in} predicates, plus any incidental UUID such as the
     * org id or product id) as canonical strings, so the modelled mapper can filter
     * its store exactly by what the service issued.
     */
    private static Set<String> uuidValuesIn(QueryWrapper<?> wrapper) {
        try {
            wrapper.getTargetSql();
        } catch (RuntimeException ignore) {
            // Param values are materialised at add-time for string-column wrappers;
            // getTargetSql is only a belt-and-braces nudge.
        }
        Set<String> ids = new HashSet<>();
        for (Object value : wrapper.getParamNameValuePairs().values()) {
            if (value instanceof UUID uuid) {
                ids.add(uuid.toString());
            } else if (value != null) {
                try {
                    ids.add(UUID.fromString(value.toString()).toString());
                } catch (IllegalArgumentException ignore) {
                    // Non-UUID predicate value (e.g. an ASIN) — not a store id.
                }
            }
        }
        return ids;
    }

    private static Set<String> asStrings(Set<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.toSet());
    }

    private static CampaignEntity campaign(UUID id, UUID storeId, String name) {
        return CampaignEntity.builder()
                .id(id)
                .storeId(storeId)
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

    private static FeishuIntegrationEntity integration(UUID orgId, UUID storeId) {
        return FeishuIntegrationEntity.builder()
                .id(UUID.randomUUID())
                .orgId(orgId)
                .storeId(storeId)
                .ownerAccountId(UUID.randomUUID())
                .provider("feishu")
                .connectionType("app")
                .status("active")
                .build();
    }
}
