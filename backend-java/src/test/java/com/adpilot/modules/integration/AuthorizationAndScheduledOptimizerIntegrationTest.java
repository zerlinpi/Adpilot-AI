package com.adpilot.modules.integration;

import com.adpilot.common.enums.ResultCode;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.AuditService;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.PermissionAspect;
import com.adpilot.common.security.PermissionChecker;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.entity.PersonalityPolicyEntity;
import com.adpilot.modules.advertising.operation.CreateOperationCommand;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.OperationSource;
import com.adpilot.modules.advertising.service.AiHostingOptimizer;
import com.adpilot.modules.advertising.service.AiPersonality;
import com.adpilot.modules.advertising.service.PersonalityPolicyService;
import com.adpilot.modules.advertising.service.PersonalityResolver;
import com.adpilot.modules.apisync.connector.PlatformConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.ApiSyncJobMapper;
import com.adpilot.modules.apisync.mapper.ApiSyncLogMapper;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.service.SyncJobRunner;
import com.adpilot.modules.apisync.service.impl.ApiSyncServiceImpl;
import com.adpilot.modules.apisync.vo.PlatformConnectionVo;
import com.adpilot.modules.automation.entity.AutomationExecutionEntity;
import com.adpilot.modules.automation.entity.AutomationRuleTemplateEntity;
import com.adpilot.modules.automation.entity.AutomationRuleTemplateLinkEntity;
import com.adpilot.modules.automation.mapper.AutomationExecutionMapper;
import com.adpilot.modules.automation.mapper.AutomationRuleTemplateLinkMapper;
import com.adpilot.modules.automation.mapper.AutomationRuleTemplateMapper;
import com.adpilot.modules.automation.service.RuleTemplateEvaluator;
import com.adpilot.modules.feishu.dto.FeishuChatBindingDto;
import com.adpilot.modules.feishu.dto.FeishuIntegrationDto;
import com.adpilot.modules.feishu.entity.FeishuChatBindingEntity;
import com.adpilot.modules.feishu.entity.FeishuIntegrationEntity;
import com.adpilot.modules.feishu.mapper.FeishuChatBindingMapper;
import com.adpilot.modules.feishu.mapper.FeishuIntegrationMapper;
import com.adpilot.modules.feishu.mapper.FeishuMessageLogMapper;
import com.adpilot.modules.feishu.mapper.FeishuNotificationRuleMapper;
import com.adpilot.modules.feishu.service.impl.FeishuServiceImpl;
import com.adpilot.modules.feishu.vo.FeishuChatBindingVo;
import com.adpilot.modules.feishu.vo.FeishuIntegrationVo;
import com.adpilot.modules.user.entity.LoginLog;
import com.adpilot.modules.user.entity.User;
import com.adpilot.modules.user.mapper.LoginLogMapper;
import com.adpilot.modules.user.mapper.UserMapper;
import com.adpilot.modules.auth.service.impl.LoginSecurityServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration-wiring tests (task 17.1) tying together the authorization guards
 * and the scheduled optimizers end-to-end.
 *
 * <p>Covers:
 * <ul>
 *   <li>Permission guards {@code user:manage}, {@code automation:manage},
 *       {@code feishu:manage}, {@code advertising:manage} enforced through the
 *       real Spring AOP {@link PermissionAspect} + {@link PermissionChecker}
 *       pipeline against a live {@link SecurityContextHolder} (Req 3.5, 5.5,
 *       9.4, 10.6, 25.6).</li>
 *   <li>{@link AiHostingOptimizer} run end-to-end: hosted-campaign load →
 *       clamped bid adjustment → persisted keyword / {@code bid_changes} /
 *       {@code automation_executions} (Req 21.2).</li>
 *   <li>{@link RuleTemplateEvaluator} run end-to-end: condition-to-action is
 *       applied exactly when the condition holds and persists an
 *       {@code automation_executions} row, never when it is false (Req 25.3).</li>
 *   <li>Platform-connection connect / test / disconnect persistence through
 *       {@link ApiSyncServiceImpl} (Req 13.x wiring).</li>
 *   <li>Feishu chat-binding persistence through {@link FeishuServiceImpl}
 *       (Req 10.6).</li>
 *   <li>Login-attempt recording through {@link LoginSecurityServiceImpl}
 *       (Req 15.3).</li>
 * </ul>
 *
 * <p><strong>Test-DB note.</strong> The project ships no test-database
 * infrastructure (no H2, no Testcontainers, no {@code @SpringBootTest}) and the
 * single Flyway migration {@code V1__init_schema.sql} is MySQL-specific
 * (JSON columns, generated columns, MySQL DDL), so it cannot be replayed into an
 * embedded engine. Following the established convention in this module
 * (see {@code LoginLoggingPropertyTest}, {@code AiHostingOptimizerTest}), the
 * "test DB" here is an in-memory store wired at the MyBatis-Plus mapper
 * boundary: {@code insert}/{@code updateById} mutate in-memory collections and
 * {@code selectList}/{@code selectById}/{@code selectOne} read them back, so
 * persistence is observable through read-back rather than by mocking each
 * return value in isolation.
 */
class AuthorizationAndScheduledOptimizerIntegrationTest {

    @BeforeAll
    static void registerEntityMetadata() {
        // LoginSecurityServiceImpl builds LambdaUpdateWrapper<User>, which needs
        // MyBatis-Plus entity metadata normally populated during mapper scanning.
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
    }

    // =====================================================================
    // A. Authorization guards through the real PermissionAspect (AOP)
    // =====================================================================

    @Nested
    @DisplayName("Authorization guards (user/automation/feishu/advertising :manage)")
    class AuthorizationGuards {

        private GuardedOperations guarded;
        private AuditService auditService;

        @BeforeEach
        void setUp() {
            auditService = mock(AuditService.class);
            PermissionAspect aspect = new PermissionAspect(new PermissionChecker(), auditService);
            AspectJProxyFactory factory = new AspectJProxyFactory(new GuardedOperations());
            factory.addAspect(aspect);
            guarded = factory.getProxy();
        }

        @org.junit.jupiter.api.AfterEach
        void clear() {
            SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("each guard permits a caller holding exactly its permission")
        void permitsHolderOfExactPermission() {
            authenticate(Set.of("operator"), List.of("user:manage"));
            assertThat(guarded.userManage()).isEqualTo("user");

            authenticate(Set.of("operator"), List.of("automation:manage"));
            assertThat(guarded.automationManage()).isEqualTo("automation");

            authenticate(Set.of("operator"), List.of("feishu:manage"));
            assertThat(guarded.feishuManage()).isEqualTo("feishu");

            authenticate(Set.of("operator"), List.of("advertising:manage"));
            assertThat(guarded.advertisingManage()).isEqualTo("advertising");
        }

        @Test
        @DisplayName("each guard rejects a caller lacking its permission with HTTP 403 and never runs the method")
        void rejectsCallerWithoutPermission() {
            // Holds an unrelated permission only.
            authenticate(Set.of("operator"), List.of("report:view"));

            assertGuardRejected(guarded::userManage, "user:manage");
            assertGuardRejected(guarded::automationManage, "automation:manage");
            assertGuardRejected(guarded::feishuManage, "feishu:manage");
            assertGuardRejected(guarded::advertisingManage, "advertising:manage");
        }

        @Test
        @DisplayName("super_admin is permitted on every guard without an explicit grant")
        void superAdminBypassesEveryGuard() {
            authenticate(Set.of("super_admin"), List.of());
            assertThat(guarded.userManage()).isEqualTo("user");
            assertThat(guarded.automationManage()).isEqualTo("automation");
            assertThat(guarded.feishuManage()).isEqualTo("feishu");
            assertThat(guarded.advertisingManage()).isEqualTo("advertising");
            verify(auditService, atLeastOnce()).recordPermit(any());
        }

        @Test
        @DisplayName("an unauthenticated caller is denied (guarded method never runs)")
        void unauthenticatedCallerDenied() {
            // No authentication in context. The aspect/PermissionChecker surfaces an
            // auth error so the guarded method never executes. (In a real request the
            // JwtAuthFilter/SecurityConfig reject with 401 before the aspect is reached.)
            SecurityContextHolder.clearContext();
            assertThatThrownBy(() -> guarded.advertisingManage())
                    .isInstanceOf(BusinessException.class);
        }

        private void assertGuardRejected(Runnable call, String permission) {
            assertThatThrownBy(call::run)
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                            .isEqualTo(ResultCode.FORBIDDEN.getCode()));
            verify(auditService).recordDeny(permission);
        }

        private void authenticate(Set<String> roles, List<String> permissions) {
            CurrentUser user = CurrentUser.builder()
                    .userId(UUID.randomUUID().toString())
                    .orgId(UUID.randomUUID().toString())
                    .email("admin@example.com")
                    .roles(roles)
                    .permissions(permissions)
                    .build();
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        }
    }

    /** Bean carrying the four guarded operations exercised through the aspect. */
    static class GuardedOperations {
        @RequirePermission("user:manage")
        public String userManage() {
            return "user";
        }

        @RequirePermission("automation:manage")
        public String automationManage() {
            return "automation";
        }

        @RequirePermission("feishu:manage")
        public String feishuManage() {
            return "feishu";
        }

        @RequirePermission("advertising:manage")
        public String advertisingManage() {
            return "advertising";
        }
    }

    // =====================================================================
    // B. AiHostingOptimizer end-to-end against the in-memory store
    // =====================================================================

    @Nested
    @DisplayName("AiHostingOptimizer scheduled run (end-to-end)")
    class HostingOptimizerEndToEnd {

        private CampaignMapper campaignMapper;
        private KeywordMapper keywordMapper;
        private PerformanceDailyMapper performanceDailyMapper;
        private OperationService operationService;
        private PersonalityResolver personalityResolver;
        private PersonalityPolicyService personalityPolicyService;
        private OperationMapper operationMapper;

        private final List<CampaignEntity> campaigns = new ArrayList<>();
        private final List<KeywordEntity> keywords = new ArrayList<>();
        private final List<CreateOperationCommand> created = new ArrayList<>();
        private final List<PerformanceDailyEntity> performance = new ArrayList<>();

        private AiHostingOptimizer optimizer;

        @BeforeEach
        void setUp() {
            campaignMapper = mock(CampaignMapper.class);
            keywordMapper = mock(KeywordMapper.class);
            performanceDailyMapper = mock(PerformanceDailyMapper.class);
            operationService = mock(OperationService.class);
            personalityResolver = mock(PersonalityResolver.class);
            personalityPolicyService = mock(PersonalityPolicyService.class);
            operationMapper = mock(OperationMapper.class);

            when(campaignMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(campaigns));
            when(keywordMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(keywords));
            when(performanceDailyMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(performance));
            when(personalityResolver.resolveForCampaign(any(CampaignEntity.class)))
                    .thenReturn(AiPersonality.BALANCED);
            when(personalityPolicyService.resolvePolicy(any())).thenReturn(PersonalityPolicyEntity.builder()
                    .scope("system").personality("balanced")
                    .maxBidIncreaseRatio(new BigDecimal("0.10"))
                    .maxBidDecreaseRatio(new BigDecimal("0.15"))
                    .approvalBidChangeRatio(new BigDecimal("0.07"))
                    .ruleVersion("balanced-v1")
                    .build());
            when(operationService.createOperation(any())).thenAnswer(inv -> {
                created.add(inv.getArgument(0));
                return null;
            });

            optimizer = new AiHostingOptimizer(campaignMapper, keywordMapper, performanceDailyMapper,
                    operationService, personalityResolver, personalityPolicyService, operationMapper,
                    new com.adpilot.modules.advertising.hosting.ReversibilityClassifier());
            ReflectionTestUtils.setField(optimizer, "lookbackDays", 14);
            ReflectionTestUtils.setField(optimizer, "minBid", new BigDecimal("0.02"));
            ReflectionTestUtils.setField(optimizer, "maxBid", new BigDecimal("1000"));
            ReflectionTestUtils.setField(optimizer, "phaseConfig", "V1");
            ReflectionTestUtils.setField(optimizer, "globalPause", false);
        }

        @Test
        @DisplayName("a hosted campaign above target ACoS emits a clamped bid-decrease ai_hosting Operation")
        void hostedCampaignAboveTargetEmitsClampedBidDecreaseOperation() {
            UUID storeId = UUID.randomUUID();
            UUID campaignId = UUID.randomUUID();
            CampaignEntity campaign = CampaignEntity.builder()
                    .id(campaignId)
                    .storeId(storeId)
                    .name("Hosted SP")
                    .hostingEnabled(true)
                    .targetAcos(new BigDecimal("25"))
                    .build();
            campaigns.add(campaign);

            KeywordEntity keyword = KeywordEntity.builder()
                    .id(UUID.randomUUID())
                    .campaignId(campaignId)
                    .storeId(storeId)
                    .keywordText("kw")
                    .status("enabled")
                    .bid(new BigDecimal("1.00"))
                    .version(0L)
                    .build();
            keywords.add(keyword);

            // Recent ACoS = 50/100 = 50% > 25% target -> bid moves down.
            performance.add(PerformanceDailyEntity.builder()
                    .campaignId(campaignId)
                    .date(LocalDate.now())
                    .spend(new BigDecimal("50"))
                    .sales(new BigDecimal("100"))
                    .build());

            AiHostingOptimizer.OptimizationSummary summary = optimizer.runOnce();

            assertThat(summary.getCampaignsProcessed()).isEqualTo(1);
            assertThat(summary.getCampaignsFailed()).isZero();
            assertThat(summary.getOperationsCreated()).isEqualTo(1);

            // One ai_hosting platform_mutation Operation emitted for the keyword bid; the confirmed
            // value is NOT written directly anymore (it changes only when the Operation goes effective).
            assertThat(created).hasSize(1);
            CreateOperationCommand cmd = created.get(0);
            assertThat(cmd.getOperationSource()).isEqualTo(OperationSource.AI_HOSTING);
            assertThat(cmd.getEntityId()).isEqualTo(keyword.getId());
            assertThat(cmd.getField()).isEqualTo("bid");
            assertThat(cmd.getPersonalityRuleVersion()).isEqualTo("balanced-v1");
            assertThat((BigDecimal) cmd.getAfterValue()).isLessThan(new BigDecimal("1.00"));
            assertThat((BigDecimal) cmd.getAfterValue()).isGreaterThanOrEqualTo(new BigDecimal("0.85"));
            verify(keywordMapper, org.mockito.Mockito.never()).updateById(any());
        }

        @Test
        @DisplayName("a hosted campaign at target ACoS is a fixpoint: no Operation emitted")
        void hostedCampaignAtTargetIsFixpoint() {
            UUID storeId = UUID.randomUUID();
            UUID campaignId = UUID.randomUUID();
            campaigns.add(CampaignEntity.builder()
                    .id(campaignId).storeId(storeId).name("Hosted SP")
                    .hostingEnabled(true).targetAcos(new BigDecimal("25")).build());
            keywords.add(KeywordEntity.builder()
                    .id(UUID.randomUUID()).campaignId(campaignId).storeId(storeId)
                    .keywordText("kw").status("enabled").bid(new BigDecimal("1.00")).version(0L).build());
            // ACoS = 25/100 = 25% == target -> fixpoint.
            performance.add(PerformanceDailyEntity.builder()
                    .campaignId(campaignId).date(LocalDate.now())
                    .spend(new BigDecimal("25")).sales(new BigDecimal("100")).build());

            AiHostingOptimizer.OptimizationSummary summary = optimizer.runOnce();

            assertThat(summary.getOperationsCreated()).isZero();
            assertThat(created).isEmpty();
        }
    }

    // =====================================================================
    // C. RuleTemplateEvaluator end-to-end against the in-memory store
    // =====================================================================

    @Nested
    @DisplayName("RuleTemplateEvaluator scheduled run (end-to-end)")
    class RuleTemplateEvaluatorEndToEnd {

        private AutomationRuleTemplateMapper templateMapper;
        private AutomationRuleTemplateLinkMapper linkMapper;
        private PerformanceDailyMapper performanceDailyMapper;
        private AutomationExecutionMapper automationExecutionMapper;

        private final List<AutomationRuleTemplateEntity> templates = new ArrayList<>();
        private final List<AutomationRuleTemplateLinkEntity> links = new ArrayList<>();
        private final List<PerformanceDailyEntity> performance = new ArrayList<>();
        private final List<AutomationExecutionEntity> executions = new ArrayList<>();

        private RuleTemplateEvaluator evaluator;

        @BeforeEach
        void setUp() {
            templateMapper = mock(AutomationRuleTemplateMapper.class);
            linkMapper = mock(AutomationRuleTemplateLinkMapper.class);
            performanceDailyMapper = mock(PerformanceDailyMapper.class);
            automationExecutionMapper = mock(AutomationExecutionMapper.class);

            when(templateMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(templates));
            when(linkMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(links));
            when(performanceDailyMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(performance));
            when(automationExecutionMapper.insert(any())).thenAnswer(inv -> {
                executions.add(inv.getArgument(0));
                return 1;
            });

            evaluator = new RuleTemplateEvaluator(templateMapper, linkMapper,
                    performanceDailyMapper, automationExecutionMapper, new ObjectMapper());
            ReflectionTestUtils.setField(evaluator, "lookbackDays", 14);
        }

        private void seedTemplate(UUID campaignId) {
            UUID templateId = UUID.randomUUID();
            templates.add(AutomationRuleTemplateEntity.builder()
                    .id(templateId)
                    .storeId(UUID.randomUUID())
                    .name("High ACoS bid down")
                    .templateType("bid")
                    .conditionJson("{\"metric\":\"acos\",\"op\":\"gt\",\"value\":25}")
                    .actionJson("{\"type\":\"bid_adjustment\",\"params\":{\"deltaPct\":-10}}")
                    .status(AutomationRuleTemplateEntity.STATUS_ENABLED)
                    .build());
            links.add(AutomationRuleTemplateLinkEntity.builder()
                    .id(UUID.randomUUID())
                    .templateId(templateId)
                    .objectType(AutomationRuleTemplateLinkEntity.OBJECT_CAMPAIGN)
                    .objectId(campaignId)
                    .build());
        }

        @Test
        @DisplayName("action is applied and persisted exactly when the condition holds")
        void appliesActionWhenConditionTrue() {
            UUID campaignId = UUID.randomUUID();
            seedTemplate(campaignId);
            // acos = 50/100 = 50% > 25 -> condition true.
            performance.add(PerformanceDailyEntity.builder()
                    .id(UUID.randomUUID()).campaignId(campaignId).date(LocalDate.now())
                    .impressions(1000L).clicks(100).orders(10)
                    .spend(new BigDecimal("50")).sales(new BigDecimal("100")).build());

            RuleTemplateEvaluator.EvaluationSummary summary = evaluator.runOnce();

            assertThat(summary.getTemplatesProcessed()).isEqualTo(1);
            assertThat(summary.getActionsApplied()).isEqualTo(1);
            assertThat(executions).hasSize(1);
            assertThat(executions.get(0).getSource()).isEqualTo("rule_template");
            assertThat(executions.get(0).getStatus()).isEqualTo("applied");
            assertThat(executions.get(0).getEntityId()).isEqualTo(campaignId);
        }

        @Test
        @DisplayName("no action is applied when the condition is false")
        void doesNotApplyActionWhenConditionFalse() {
            UUID campaignId = UUID.randomUUID();
            seedTemplate(campaignId);
            // acos = 10/100 = 10% <= 25 -> condition false.
            performance.add(PerformanceDailyEntity.builder()
                    .id(UUID.randomUUID()).campaignId(campaignId).date(LocalDate.now())
                    .impressions(1000L).clicks(100).orders(10)
                    .spend(new BigDecimal("10")).sales(new BigDecimal("100")).build());

            RuleTemplateEvaluator.EvaluationSummary summary = evaluator.runOnce();

            assertThat(summary.getActionsApplied()).isZero();
            assertThat(executions).isEmpty();
        }
    }

    // =====================================================================
    // D. Platform-connection connect / test / disconnect persistence
    // =====================================================================

    @Nested
    @DisplayName("Platform connection test/disconnect (end-to-end)")
    class PlatformConnectionEndToEnd {

        private final Map<UUID, PlatformConnectionEntity> store = new LinkedHashMap<>();
        private PlatformConnectionMapper connectionMapper;
        private PlatformConnector connector;
        private ApiSyncServiceImpl service;

        @BeforeEach
        void setUp() {
            connectionMapper = mock(PlatformConnectionMapper.class);
            connector = mock(PlatformConnector.class);

            when(connectionMapper.insert(any())).thenAnswer(inv -> {
                PlatformConnectionEntity e = inv.getArgument(0);
                if (e.getId() == null) {
                    e.setId(UUID.randomUUID());
                }
                store.put(e.getId(), e);
                return 1;
            });
            when(connectionMapper.updateById(any())).thenAnswer(inv -> {
                PlatformConnectionEntity e = inv.getArgument(0);
                store.put(e.getId(), e);
                return 1;
            });
            PlatformConnectionEntity configured = PlatformConnectionEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(UUID.randomUUID())
                    .platform("amazon_ads")
                    .connectionName("Amazon Ads")
                    .configEncrypted("{}")
                    .status("configured")
                    .build();
            store.put(configured.getId(), configured);

            // findByPlatform uses selectOne(wrapper); the store holds a single platform here.
            when(connectionMapper.selectOne(any())).thenAnswer(inv ->
                    store.values().stream().findFirst().orElse(null));

            service = new ApiSyncServiceImpl(connectionMapper, mock(ApiSyncJobMapper.class),
                    mock(ApiSyncLogMapper.class), mock(CryptoUtil.class), connector,
                    new ObjectMapper(), mock(SyncJobRunner.class),
                    mock(com.adpilot.modules.store.mapper.StoreMapper.class),
                    mock(com.adpilot.modules.store.mapper.MarketplaceMapper.class),
                    mock(com.adpilot.modules.store.mapper.UserStoreMapper.class),
                    mock(com.adpilot.modules.audit.service.AuditLogService.class));

        }

        @Test
        @DisplayName("connect persists a connected status, test returns the connector message, disconnect persists disconnected")
        void connectTestDisconnectLifecycle() {
            when(connector.test(any(), any()))
                    .thenReturn(PlatformConnector.TestResult.ok("Amazon 凭证有效"));

            PlatformConnectionVo connected = service.connectPlatform("amazon_ads", UUID.randomUUID().toString());
            assertThat(connected.getStatus()).isEqualTo("connected");
            assertThat(connected.getMessage()).isEqualTo("Amazon 凭证有效");
            assertThat(store).hasSize(1);
            assertThat(store.values().iterator().next().getStatus()).isEqualTo("connected");

            String testMessage = service.testPlatformByKey("amazon_ads");
            assertThat(testMessage).isEqualTo("Amazon 凭证有效");

            PlatformConnectionVo disconnected = service.disconnectPlatform("amazon_ads");
            assertThat(disconnected.getStatus()).isEqualTo("disconnected");
            // Persisted: the stored entity is now disconnected.
            assertThat(store.values().iterator().next().getStatus()).isEqualTo("disconnected");
        }

        @Test
        @DisplayName("test against an unconfigured platform reports not-connected without throwing")
        void testUnconfiguredPlatform() {
            when(connectionMapper.selectOne(any())).thenReturn(null);
            String message = service.testPlatformByKey("shopify");
            assertThat(message).contains("shopify");
        }
    }

    // =====================================================================
    // E. Feishu chat-binding persistence
    // =====================================================================

    @Nested
    @DisplayName("Feishu chat-binding persistence (end-to-end)")
    class FeishuBindingEndToEnd {

        private final Map<UUID, FeishuIntegrationEntity> integrations = new LinkedHashMap<>();
        private final List<FeishuChatBindingEntity> bindings = new ArrayList<>();

        /** The org the authenticated caller belongs to; the integration is created under it. */
        private final UUID orgId = UUID.randomUUID();

        private FeishuIntegrationMapper integrationMapper;
        private FeishuChatBindingMapper chatBindingMapper;
        private FeishuServiceImpl service;

        @BeforeEach
        void setUp() {
            // FeishuServiceImpl enforces cross-org isolation (requireIntegrationWithOrgCheck):
            // mutating an integration's bindings requires an authenticated caller whose org
            // owns the integration. Establish that security context for this end-to-end flow.
            CurrentUser caller = CurrentUser.builder()
                    .userId(UUID.randomUUID().toString())
                    .orgId(orgId.toString())
                    .email("ops@example.com")
                    .roles(java.util.Set.of())
                    .build();
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(caller, null, caller.getAuthorities()));

            integrationMapper = mock(FeishuIntegrationMapper.class);
            chatBindingMapper = mock(FeishuChatBindingMapper.class);

            when(integrationMapper.insert(any())).thenAnswer(inv -> {
                FeishuIntegrationEntity e = inv.getArgument(0);
                if (e.getId() == null) {
                    e.setId(UUID.randomUUID());
                }
                integrations.put(e.getId(), e);
                return 1;
            });
            when(integrationMapper.selectById(any())).thenAnswer(inv ->
                    integrations.get(toUuid(inv.getArgument(0))));
            when(chatBindingMapper.insert(any())).thenAnswer(inv -> {
                FeishuChatBindingEntity e = inv.getArgument(0);
                if (e.getId() == null) {
                    e.setId(UUID.randomUUID());
                }
                bindings.add(e);
                return 1;
            });
            when(chatBindingMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(bindings));

            service = new FeishuServiceImpl(integrationMapper, mock(FeishuMessageLogMapper.class),
                    chatBindingMapper, mock(FeishuNotificationRuleMapper.class),
                    mock(com.adpilot.modules.feishu.mapper.FeishuActionRequestMapper.class),
                    mock(com.adpilot.modules.feishu.client.FeishuApiClient.class),
                    mock(com.adpilot.common.utils.CryptoUtil.class),
                    new com.fasterxml.jackson.databind.ObjectMapper(),
                    mock(com.adpilot.modules.store.mapper.StoreMapper.class),
                    mock(com.adpilot.common.security.DataScopeService.class),
                    new com.adpilot.modules.feishu.support.FeishuWebhookValidator(
                            "open.feishu.cn,open.larksuite.com"),
                    new com.adpilot.common.resilience.CircuitBreaker(false, 5, 30),
                    mock(com.adpilot.modules.audit.service.AuditLogService.class));
        }

        @AfterEach
        void tearDown() {
            // Avoid leaking the authenticated context into sibling tests.
            SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("creating a chat binding persists it and it is returned by list")
        void chatBindingIsPersistedAndListed() {
            FeishuIntegrationDto integrationDto = new FeishuIntegrationDto();
            integrationDto.setOrgId(orgId.toString());
            integrationDto.setAppId("cli_app");
            integrationDto.setAppSecretEncrypted("secret");
            FeishuIntegrationVo integration = service.connect(integrationDto, UUID.randomUUID().toString());
            assertThat(integration.getId()).isNotNull();

            FeishuChatBindingDto bindingDto = new FeishuChatBindingDto();
            bindingDto.setChatId("oc_chat_123");
            bindingDto.setChatName("运营群");
            FeishuChatBindingVo saved = service.createChatBinding(integration.getId(), bindingDto);

            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getChatId()).isEqualTo("oc_chat_123");
            assertThat(bindings).hasSize(1);

            List<FeishuChatBindingVo> listed = service.listChatBindings(integration.getId());
            assertThat(listed).hasSize(1);
            assertThat(listed.get(0).getChatId()).isEqualTo("oc_chat_123");
            assertThat(listed.get(0).getChatName()).isEqualTo("运营群");
        }

        private UUID toUuid(Object value) {
            return value instanceof UUID u ? u : UUID.fromString(value.toString());
        }
    }

    // =====================================================================
    // F. Login-attempt recording
    // =====================================================================

    @Nested
    @DisplayName("Login-attempt recording (end-to-end)")
    class LoginAttemptRecording {

        private final List<LoginLog> persisted = new ArrayList<>();
        private LoginSecurityServiceImpl service;

        @BeforeEach
        void setUp() {
            UserMapper userMapper = mock(UserMapper.class);
            when(userMapper.update(isNull(), any())).thenReturn(1);
            LoginLogMapper loginLogMapper = mock(LoginLogMapper.class);
            when(loginLogMapper.insert(any(LoginLog.class))).thenAnswer(inv -> {
                persisted.add(inv.getArgument(0));
                return 1;
            });

            service = new LoginSecurityServiceImpl(userMapper, loginLogMapper);
            ReflectionTestUtils.setField(service, "maxFailedAttempts", 5);
            ReflectionTestUtils.setField(service, "lockoutDurationMinutes", 15L);
        }

        @Test
        @DisplayName("a successful and a failed attempt each record exactly one outcome entry")
        void recordsSuccessAndFailureAttempts() {
            User user = User.builder()
                    .id(UUID.randomUUID())
                    .orgId(UUID.randomUUID())
                    .email("ops@example.com")
                    .name("Ops")
                    .failedLoginCount(0)
                    .build();

            service.recordSuccessfulLogin(user, user.getEmail(), "203.0.113.7", "JUnit/1.0");
            service.recordFailedAttempt(user, user.getEmail(), "203.0.113.7", "JUnit/1.0", "Invalid credentials");

            assertThat(persisted).hasSize(2);
            assertThat(persisted.get(0).getLoginStatus()).isEqualTo("success");
            assertThat(persisted.get(0).getFailureReason()).isNull();
            assertThat(persisted.get(1).getLoginStatus()).isEqualTo("failed");
            assertThat(persisted.get(1).getFailureReason()).contains("Invalid credentials");
        }
    }
}
