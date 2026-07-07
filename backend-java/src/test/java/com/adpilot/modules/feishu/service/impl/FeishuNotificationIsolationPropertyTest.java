package com.adpilot.modules.feishu.service.impl;

import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.feishu.client.FeishuApiClient;
import com.adpilot.modules.feishu.entity.FeishuChatBindingEntity;
import com.adpilot.modules.feishu.entity.FeishuIntegrationEntity;
import com.adpilot.modules.feishu.entity.FeishuMessageLogEntity;
import com.adpilot.modules.feishu.mapper.FeishuActionRequestMapper;
import com.adpilot.modules.feishu.mapper.FeishuChatBindingMapper;
import com.adpilot.modules.feishu.mapper.FeishuIntegrationMapper;
import com.adpilot.modules.feishu.mapper.FeishuMessageLogMapper;
import com.adpilot.modules.feishu.mapper.FeishuNotificationRuleMapper;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for per-store, per-account isolation of Feishu notification
 * target resolution in {@link FeishuServiceImpl} (task 9.1 tightened resolution).
 *
 * <p>Feature: multistore-ai-ads-operations, Property 16: 飞书通知按店铺与账号隔离
 *
 * <p>Validates: Requirements 7.2, 7.3.
 *
 * <p>For any store and the multiple Feishu integrations in the system, the
 * integration selected to send a store's notification must satisfy: its
 * {@code store_id} equals the store AND (by account ownership) its
 * {@code owner_account_id} equals the store's owning account. The system never
 * uses one account's credentials for a store it has not bound, nor reuses an
 * integration across accounts.
 *
 * <p>Following {@code TableViewIsolationPropertyTest}: the
 * {@link FeishuIntegrationMapper} is modelled as a scope-filtered store driven by
 * the exact {@link LambdaQueryWrapper} predicate the service issues. Each property
 * asserts BOTH halves of the invariant:
 * <ul>
 *   <li>the integration actually dispatched through is the one matching
 *       {@code (store_id, owner_account_id)} and never a cross-store/cross-account
 *       decoy; and</li>
 *   <li>the issued query predicate itself carries {@code store_id == store} and
 *       {@code owner_account_id == owning account}, never another store's or
 *       account's id.</li>
 * </ul>
 */
class FeishuNotificationIsolationPropertyTest {

    static {
        // Register entity metadata so MyBatis-Plus can resolve lambda columns and
        // materialise bound parameter values for query-wrapper introspection,
        // without a running Spring/MyBatis context.
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, FeishuIntegrationEntity.class);
        TableInfoHelper.initTableInfo(assistant, FeishuChatBindingEntity.class);
    }

    /**
     * Feature: multistore-ai-ads-operations, Property 16: 飞书通知按店铺与账号隔离
     *
     * <p>Validates: Requirements 7.2, 7.3.
     *
     * <p>With a store-bound integration owned by the store's account present
     * alongside cross-store and cross-account decoys, resolution selects exactly
     * the matching integration: it dispatches through that integration, and the
     * issued query is scoped to {@code (store_id, owner_account_id)}.
     */
    @Property(tries = 100)
    void resolveTargetSelectsOnlyTheStoreAndAccountOwnedIntegration(
            @ForAll("distinctIds") List<UUID> ids) {

        UUID storeId = ids.get(0);
        UUID ownerAccount = ids.get(1);
        UUID otherAccount = ids.get(2);
        UUID otherStore = ids.get(3);
        UUID orgId = ids.get(4);

        Fixture f = new Fixture();

        // The single legitimate target: this store, owned by this store's account.
        FeishuIntegrationEntity match =
                appIntegration(storeId, ownerAccount, orgId, "app-match");
        // Decoys that must never be selected.
        FeishuIntegrationEntity sameStoreOtherAccount =
                appIntegration(storeId, otherAccount, orgId, "app-other-acct");
        FeishuIntegrationEntity otherStoreSameAccount =
                appIntegration(otherStore, ownerAccount, orgId, "app-other-store");

        List<FeishuIntegrationEntity> world =
                List.of(match, sameStoreOtherAccount, otherStoreSameAccount);

        f.stubStore(storeId, orgId, ownerAccount);
        f.stubIntegrationStore(world);

        boolean sent = f.service.pushAlert(storeId, "title", "body");

        // Result isolation: a matching target exists, so a message is sent through it.
        assertThat(sent).isTrue();
        assertThat(f.dispatchedIntegrationIds()).containsExactly(match.getId());
        assertThat(f.dispatchedIntegrationIds())
                .doesNotContain(sameStoreOtherAccount.getId(), otherStoreSameAccount.getId());

        // Query-predicate isolation: the issued predicate is scoped to this store
        // and this store's account, and carries no other store/account id.
        Map<String, Object> predicate = f.resolvePredicate();
        assertThat(predicate.get("store_id")).isEqualTo(storeId);
        assertThat(predicate.get("owner_account_id")).isEqualTo(ownerAccount);
        assertThat(predicate.values())
                .doesNotContain(otherAccount)
                .doesNotContain(otherStore);
    }

    /**
     * Feature: multistore-ai-ads-operations, Property 16: 飞书通知按店铺与账号隔离
     *
     * <p>Validates: Requirements 7.2, 7.3.
     *
     * <p>When no integration matches both this store and this store's account (only
     * a same-store/other-account and an other-store/same-account decoy exist),
     * nothing is sent — the system never reuses another account's credentials.
     */
    @Property(tries = 100)
    void resolveTargetNeverReusesAnotherStoreOrAccountIntegration(
            @ForAll("distinctIds") List<UUID> ids) {

        UUID storeId = ids.get(0);
        UUID ownerAccount = ids.get(1);
        UUID otherAccount = ids.get(2);
        UUID otherStore = ids.get(3);
        UUID orgId = ids.get(4);

        Fixture f = new Fixture();

        // Only decoys exist: same store but different account, and same account but
        // a different store. Neither satisfies (store_id, owner_account_id).
        FeishuIntegrationEntity sameStoreOtherAccount =
                appIntegration(storeId, otherAccount, orgId, "app-other-acct");
        FeishuIntegrationEntity otherStoreSameAccount =
                appIntegration(otherStore, ownerAccount, orgId, "app-other-store");

        f.stubStore(storeId, orgId, ownerAccount);
        f.stubIntegrationStore(List.of(sameStoreOtherAccount, otherStoreSameAccount));

        boolean sent = f.service.pushAlert(storeId, "title", "body");

        // No legitimate target -> no send, no cross-account/cross-store dispatch.
        assertThat(sent).isFalse();
        assertThat(f.dispatchedIntegrationIds()).isEmpty();

        // The predicate still pins the account dimension to this store's owner.
        Map<String, Object> predicate = f.resolvePredicate();
        assertThat(predicate.get("store_id")).isEqualTo(storeId);
        assertThat(predicate.get("owner_account_id")).isEqualTo(ownerAccount);
        assertThat(predicate.values()).doesNotContain(otherAccount);
    }

    /**
     * Feature: multistore-ai-ads-operations, Property 16: 飞书通知按店铺与账号隔离
     *
     * <p>Validates: Requirements 7.2, 7.3.
     *
     * <p>The AI-notification chat-binding path dispatches only through an
     * integration owned by the store's account: a chat binding that points at a
     * cross-account integration is skipped, never reused.
     */
    @Property(tries = 100)
    void pushAiNotificationDispatchesOnlyThroughStoreAccountOwnedIntegration(
            @ForAll("distinctIds") List<UUID> ids) {

        UUID storeId = ids.get(0);
        UUID ownerAccount = ids.get(1);
        UUID otherAccount = ids.get(2);
        UUID orgId = ids.get(4);

        Fixture f = new Fixture();

        FeishuIntegrationEntity match =
                appIntegration(storeId, ownerAccount, orgId, "app-match");
        FeishuIntegrationEntity crossAccount =
                appIntegration(storeId, otherAccount, orgId, "app-cross-acct");

        f.stubStore(storeId, orgId, ownerAccount);
        f.stubIntegrationById(List.of(match, crossAccount));

        // Two active bindings for this store: one to the legitimate integration and
        // one to a cross-account integration that must be skipped.
        FeishuChatBindingEntity bindMatch = binding(storeId, match.getId(), "chat-match");
        FeishuChatBindingEntity bindCross = binding(storeId, crossAccount.getId(), "chat-cross");
        f.stubBindingStore(List.of(bindMatch, bindCross));

        boolean sent = f.service.pushAiNotification(storeId, "title", "body");

        assertThat(sent).isTrue();
        // Only the store-account-owned integration is dispatched through.
        assertThat(f.dispatchedIntegrationIds()).containsExactly(match.getId());
        assertThat(f.dispatchedIntegrationIds()).doesNotContain(crossAccount.getId());

        // The chat-binding query is scoped to this store.
        Map<String, Object> bindingPredicate = f.bindingPredicate();
        assertThat(bindingPredicate.get("store_id")).isEqualTo(storeId);
    }

    // --- fixture -----------------------------------------------------------

    /**
     * Wires a {@link FeishuServiceImpl} over mocked collaborators and models the
     * integration/binding mappers as scope-filtered stores driven by the exact
     * predicate the service issues.
     */
    private static final class Fixture {
        final FeishuIntegrationMapper integrationMapper = Mockito.mock(FeishuIntegrationMapper.class);
        final FeishuMessageLogMapper messageLogMapper = Mockito.mock(FeishuMessageLogMapper.class);
        final FeishuChatBindingMapper chatBindingMapper = Mockito.mock(FeishuChatBindingMapper.class);
        final FeishuNotificationRuleMapper notificationRuleMapper = Mockito.mock(FeishuNotificationRuleMapper.class);
        final FeishuActionRequestMapper actionRequestMapper = Mockito.mock(FeishuActionRequestMapper.class);
        final FeishuApiClient apiClient = Mockito.mock(FeishuApiClient.class);
        final CryptoUtil cryptoUtil = Mockito.mock(CryptoUtil.class);
        final ObjectMapper objectMapper = new ObjectMapper();
        final StoreMapper storeMapper = Mockito.mock(StoreMapper.class);
        final DataScopeService dataScopeService = Mockito.mock(DataScopeService.class);

        final FeishuServiceImpl service = new FeishuServiceImpl(
                integrationMapper, messageLogMapper, chatBindingMapper, notificationRuleMapper,
                actionRequestMapper, apiClient, cryptoUtil, objectMapper, storeMapper, dataScopeService,
                new com.adpilot.modules.feishu.support.FeishuWebhookValidator(
                        "open.feishu.cn,open.larksuite.com"),
                new com.adpilot.common.resilience.CircuitBreaker(false, 5, 30),
                Mockito.mock(com.adpilot.modules.audit.service.AuditLogService.class));

        Fixture() {
            // Make dispatch succeed without touching the network.
            when(cryptoUtil.decrypt(any())).thenReturn("secret");
            when(apiClient.tenantAccessToken(any(), any())).thenReturn("token");
            when(apiClient.buildTextContent(any())).thenReturn("{\"text\":\"x\"}");
        }

        void stubStore(UUID storeId, UUID orgId, UUID ownerAccount) {
            StoreEntity store = StoreEntity.builder()
                    .id(storeId).orgId(orgId).name("s").marketplaceId(UUID.randomUUID())
                    .createdBy(ownerAccount).build();
            when(storeMapper.selectById(storeId)).thenReturn(store);
        }

        /** Model the integration mapper's {@code selectList} as a predicate-filtered store. */
        void stubIntegrationStore(List<FeishuIntegrationEntity> world) {
            when(integrationMapper.selectList(any())).thenAnswer(inv ->
                    filterByPredicate(world, inv.getArgument(0)));
        }

        void stubIntegrationById(List<FeishuIntegrationEntity> world) {
            for (FeishuIntegrationEntity e : world) {
                when(integrationMapper.selectById(e.getId())).thenReturn(e);
            }
        }

        /** Model the chat-binding mapper's {@code selectList} as a store-scoped store. */
        void stubBindingStore(List<FeishuChatBindingEntity> bindings) {
            when(chatBindingMapper.selectList(any())).thenAnswer(inv -> {
                Map<String, Object> p = predicateValues(inv.getArgument(0));
                return bindings.stream()
                        .filter(b -> !p.containsKey("store_id") || p.get("store_id").equals(b.getStoreId()))
                        .filter(b -> !p.containsKey("status") || p.get("status").equals(b.getStatus()))
                        .collect(Collectors.toList());
            });
        }

        List<UUID> dispatchedIntegrationIds() {
            ArgumentCaptor<FeishuMessageLogEntity> captor =
                    ArgumentCaptor.forClass(FeishuMessageLogEntity.class);
            verify(messageLogMapper, Mockito.atLeast(0)).insert(captor.capture());
            return captor.getAllValues().stream()
                    .filter(l -> "sent".equals(l.getStatus()))
                    .map(FeishuMessageLogEntity::getFeishuIntegrationId)
                    .collect(Collectors.toList());
        }

        Map<String, Object> resolvePredicate() {
            ArgumentCaptor<LambdaQueryWrapper<FeishuIntegrationEntity>> captor =
                    ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(integrationMapper).selectList(captor.capture());
            return predicateValues(captor.getValue());
        }

        Map<String, Object> bindingPredicate() {
            ArgumentCaptor<LambdaQueryWrapper<FeishuChatBindingEntity>> captor =
                    ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(chatBindingMapper).selectList(captor.capture());
            return predicateValues(captor.getValue());
        }
    }

    /**
     * Filter a world of integrations by the equality predicates the service placed
     * into its {@link LambdaQueryWrapper}, plus the (connection_type=webhook OR
     * default_chat_id IS NOT NULL) guard — exactly as the real SQL would.
     */
    private static List<FeishuIntegrationEntity> filterByPredicate(
            List<FeishuIntegrationEntity> world, LambdaQueryWrapper<FeishuIntegrationEntity> wrapper) {
        Map<String, Object> p = predicateValues(wrapper);
        boolean ownerIsNull = wrapper.getSqlSegment().contains("owner_account_id IS NULL");
        return world.stream().filter(e -> {
            if (p.containsKey("status") && !p.get("status").equals(e.getStatus())) {
                return false;
            }
            if (p.containsKey("store_id") && !p.get("store_id").equals(e.getStoreId())) {
                return false;
            }
            if (ownerIsNull) {
                if (e.getOwnerAccountId() != null) {
                    return false;
                }
            } else if (p.containsKey("owner_account_id")
                    && !p.get("owner_account_id").equals(e.getOwnerAccountId())) {
                return false;
            }
            if (p.containsKey("org_id") && !p.get("org_id").equals(e.getOrgId())) {
                return false;
            }
            // (connection_type = 'webhook' OR default_chat_id IS NOT NULL)
            return "webhook".equalsIgnoreCase(e.getConnectionType()) || e.getDefaultChatId() != null;
        }).limit(20).collect(Collectors.toList());
    }

    private static final Pattern EQ_PREDICATE =
            Pattern.compile("(\\w+)\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}");

    /**
     * Extract the column→value map the service bound into a {@link LambdaQueryWrapper}
     * by parsing the rendered SQL segment and resolving each placeholder against the
     * materialised parameter pairs. Mirrors how the real query discriminates rows.
     */
    private static Map<String, Object> predicateValues(LambdaQueryWrapper<?> wrapper) {
        // Force MyBatis-Plus to materialise bound parameter values before reading.
        wrapper.getTargetSql();
        Map<String, Object> pairs = wrapper.getParamNameValuePairs();
        Map<String, Object> result = new LinkedHashMap<>();
        Matcher m = EQ_PREDICATE.matcher(wrapper.getSqlSegment());
        while (m.find()) {
            result.put(m.group(1), pairs.get(m.group(2)));
        }
        return result;
    }

    // --- builders ----------------------------------------------------------

    private static FeishuIntegrationEntity appIntegration(UUID storeId, UUID ownerAccountId,
                                                          UUID orgId, String appId) {
        return FeishuIntegrationEntity.builder()
                .id(UUID.randomUUID())
                .orgId(orgId)
                .storeId(storeId)
                .ownerAccountId(ownerAccountId)
                .provider("feishu")
                .connectionType("app")
                .appId(appId)
                .appSecretEncrypted("enc")
                .defaultChatId("chat-" + appId)
                .status("active")
                .build();
    }

    private static FeishuChatBindingEntity binding(UUID storeId, UUID integrationId, String chatId) {
        return FeishuChatBindingEntity.builder()
                .id(UUID.randomUUID())
                .feishuIntegrationId(integrationId)
                .storeId(storeId)
                .chatId(chatId)
                .status("active")
                .build();
    }

    // --- generators --------------------------------------------------------

    /** Five mutually-distinct ids: store, owner account, other account, other store, org. */
    @Provide
    Arbitrary<List<UUID>> distinctIds() {
        return Arbitraries.randomValue(r -> UUID.randomUUID())
                .list().ofSize(5).uniqueElements();
    }
}
