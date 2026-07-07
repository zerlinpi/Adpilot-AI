package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.OperationOutboxMapper;
import com.adpilot.modules.advertising.operation.OperationStateMachine;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import net.jqwik.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link KillSwitchServiceImpl#isActiveForCampaign}.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 45: Kill switch stops generation and submission
 *
 * <p><b>Validates: Requirements 35.3, 40.6</b>
 *
 * <p>The kill switch hierarchy check {@code isActiveForCampaign(storeId, campaignId)} evaluates
 * four levels — system, organization, store, and campaign. If a kill switch is active at ANY
 * level, generation and submission must stop, so the method returns {@code true}. Only when NO
 * level has an active kill switch may work continue, returning {@code false}.</p>
 *
 * <p>This test models the per-scope kill-switch state by overriding the per-scope predicate
 * {@link KillSwitchServiceImpl#isActive(String, UUID)} (whose own behavior is covered by
 * {@code KillSwitchServiceImplTest}) so it exercises the hierarchy aggregation and short-circuit
 * logic of {@code isActiveForCampaign} across all 2^4 level combinations.</p>
 *
 * <p>Properties tested:</p>
 * <ol>
 *   <li><b>Any active level halts work:</b> if any of system/org/store/campaign is active, returns true.</li>
 *   <li><b>No active level allows work:</b> if none are active, returns false.</li>
 *   <li><b>System short-circuit:</b> a system-level kill switch alone returns true regardless of
 *       the org/store/campaign configuration.</li>
 *   <li><b>Determinism:</b> repeated calls for the same configuration always yield the same result.</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 45: Kill switch stops generation and submission")
class KillSwitchEnforcementPropertyTest {

    /**
     * Immutable description of which hierarchy levels currently have an active kill switch.
     */
    private record KillSwitchConfig(boolean system, boolean organization, boolean store, boolean campaign) {
        boolean anyActive() {
            return system || organization || store || campaign;
        }

        boolean activeForScope(String scope) {
            return switch (scope) {
                case "system" -> system;
                case "organization" -> organization;
                case "store" -> store;
                case "campaign" -> campaign;
                default -> false;
            };
        }
    }

    /**
     * Builds a {@link KillSwitchServiceImpl} whose per-scope predicate reflects {@code config}.
     *
     * <p>The store mapper resolves a store with {@code orgId} so the organization level is reachable
     * during the hierarchy walk.</p>
     */
    private KillSwitchServiceImpl buildService(KillSwitchConfig config, UUID orgId) {
        KillSwitchMapper killSwitchMapper = mock(KillSwitchMapper.class);
        OperationMapper operationMapper = mock(OperationMapper.class);
        OperationOutboxMapper outboxMapper = mock(OperationOutboxMapper.class);
        OperationStateMachine stateMachine = new OperationStateMachine();
        HostingApprovalService approvalService = mock(HostingApprovalService.class);
        StoreMapper storeMapper = mock(StoreMapper.class);

        when(storeMapper.selectById(any())).thenAnswer(invocation -> {
            StoreEntity store = new StoreEntity();
            store.setId(invocation.getArgument(0));
            store.setOrgId(orgId);
            return store;
        });

        return new KillSwitchServiceImpl(
                killSwitchMapper,
                operationMapper,
                outboxMapper,
                stateMachine,
                approvalService,
                storeMapper,
                mock(com.adpilot.modules.audit.service.AuditLogService.class)) {
            @Override
            public boolean isActive(String scope, UUID scopeId) {
                // Model the kill-switch state per scope level; stateless and deterministic.
                return config.activeForScope(scope);
            }
        };
    }

    // ── Property 1 & 2: any active level halts work; no active level allows work ──

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 45: Kill switch stops generation and submission
     *
     * <p><b>Validates: Requirements 35.3, 40.6</b></p>
     *
     * <p>{@code isActiveForCampaign} returns true if and only if at least one hierarchy level
     * (system, organization, store, or campaign) has an active kill switch. When any level is
     * active, generation/submission must stop; when none are active, work may continue.</p>
     */
    @Property(tries = 256)
    void activeAtAnyLevelStopsWorkOtherwiseProceeds(
            @ForAll boolean system,
            @ForAll boolean organization,
            @ForAll boolean store,
            @ForAll boolean campaign) {

        KillSwitchConfig config = new KillSwitchConfig(system, organization, store, campaign);
        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();

        KillSwitchServiceImpl service = buildService(config, orgId);

        boolean result = service.isActiveForCampaign(storeId, campaignId);

        assertThat(result)
                .as("isActiveForCampaign must be true iff any level is active (system=%s org=%s store=%s campaign=%s)",
                        system, organization, store, campaign)
                .isEqualTo(config.anyActive());
    }

    // ── Property 3: system-level short-circuit ─────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 45: Kill switch stops generation and submission
     *
     * <p><b>Validates: Requirements 35.3</b></p>
     *
     * <p>An active system-level kill switch alone is sufficient to halt work: regardless of the
     * org/store/campaign configuration, {@code isActiveForCampaign} returns true.</p>
     */
    @Property(tries = 100)
    void systemLevelKillSwitchShortCircuits(
            @ForAll boolean organization,
            @ForAll boolean store,
            @ForAll boolean campaign) {

        KillSwitchConfig config = new KillSwitchConfig(true, organization, store, campaign);
        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();

        KillSwitchServiceImpl service = buildService(config, orgId);

        assertThat(service.isActiveForCampaign(storeId, campaignId))
                .as("An active system-level kill switch must stop work regardless of lower levels")
                .isTrue();
    }

    // ── Property 4: determinism ────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 45: Kill switch stops generation and submission
     *
     * <p><b>Validates: Requirements 35.3, 40.6</b></p>
     *
     * <p>The hierarchy check is deterministic: for a fixed kill-switch configuration and inputs,
     * repeated evaluations always return the same result.</p>
     */
    @Property(tries = 100)
    void checkIsDeterministicForSameConfiguration(
            @ForAll boolean system,
            @ForAll boolean organization,
            @ForAll boolean store,
            @ForAll boolean campaign) {

        KillSwitchConfig config = new KillSwitchConfig(system, organization, store, campaign);
        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();

        KillSwitchServiceImpl service = buildService(config, orgId);

        boolean first = service.isActiveForCampaign(storeId, campaignId);
        boolean second = service.isActiveForCampaign(storeId, campaignId);
        boolean third = service.isActiveForCampaign(storeId, campaignId);

        assertThat(first)
                .as("Repeated evaluations must be deterministic for the same configuration")
                .isEqualTo(second)
                .isEqualTo(third);
    }
}
