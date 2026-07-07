package com.adpilot.modules.apisync.controller;

import com.adpilot.common.security.AuditService;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.PermissionAspect;
import com.adpilot.common.security.PermissionChecker;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.operation.OperationJsonCodec;
import com.adpilot.modules.advertising.operation.OperationMachineValues;
import com.adpilot.modules.advertising.operation.OperationResult;
import com.adpilot.modules.advertising.operation.OperationScope;
import com.adpilot.modules.advertising.operation.OverlayField;
import com.adpilot.modules.advertising.operation.PendingOverlayServiceImpl;
import com.adpilot.modules.advertising.operation.SyncState;
import com.adpilot.modules.advertising.mapper.OperationPendingChangeMapper;
import com.adpilot.modules.advertising.support.PendingOverlayRow;
import com.adpilot.modules.apisync.dto.GoogleAdsAdjustRequest;
import com.adpilot.modules.apisync.dto.GoogleAdsCampaignCreateRequest;
import com.adpilot.modules.apisync.service.GoogleAdsManualOperationService;
import com.adpilot.modules.apisync.service.impl.GoogleAdsManualOperationServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the manual Google Ads write surface (platform-workspace-rbac task 9.3).
 *
 * <p>Validates: Requirements 7.5, 7.6.
 *
 * <p>Two behaviors are covered:
 * <ul>
 *   <li><b>Req 7.5 — permission rejection.</b> {@link GoogleAdsManualOperationController}'s
 *       {@code @RequirePermission("advertising:independent_site:operate")} gate is exercised
 *       through the real {@link PermissionAspect}/{@link PermissionChecker} (wired with an
 *       AspectJ proxy, the same enforcement Spring applies at runtime). A caller lacking the
 *       independent-site advertising Functional_Permission is rejected with HTTP 403
 *       <em>before the controller body runs</em>, so the
 *       {@link GoogleAdsManualOperationService} is never invoked and no Operation is created.</li>
 *   <li><b>Req 7.6 — pending overlay.</b> While a Google Ads change Operation is in an
 *       Unsettled_State, the change surfaces through the existing
 *       {@link PendingOverlayServiceImpl} alongside the platform-confirmed value.</li>
 * </ul>
 */
class GoogleAdsManualOperationControllerTest {

    /** The independent-site advertising Functional_Permission gating the endpoints (Req 7.5, 14.2). */
    private static final String INDEPENDENT_ADS_PERMISSION = "advertising:independent_site:operate";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Req 7.5 — permission rejection: 403 and NO Operation created
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Req 7.5 — missing independent-site advertising permission")
    class PermissionRejection {

        private GoogleAdsManualOperationService service;
        private GoogleAdsManualOperationController proxy;

        private void wireController() {
            // The service is mocked so we can prove it is NEVER touched when the gate denies.
            service = Mockito.mock(GoogleAdsManualOperationService.class);
            GoogleAdsManualOperationController target = new GoogleAdsManualOperationController(service);

            // Wrap the real controller with the real PermissionAspect/PermissionChecker, exactly
            // as Spring does at runtime, so the @RequirePermission gate actually runs.
            AspectJProxyFactory factory = new AspectJProxyFactory(target);
            factory.addAspect(new PermissionAspect(new PermissionChecker(), Mockito.mock(AuditService.class)));
            proxy = factory.getProxy();
        }

        private void authenticateWith(Set<String> roles, List<String> permissions) {
            CurrentUser principal = CurrentUser.builder()
                    .userId(UUID.randomUUID().toString())
                    .email("operator@example.com")
                    .orgId(UUID.randomUUID().toString())
                    .name("Operator")
                    .roles(roles)
                    .permissions(permissions)
                    .build();
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        }

        @Test
        @DisplayName("createCampaign is rejected with 403 and no Operation is created")
        void createCampaignRejectedWhenPermissionMissing() {
            wireController();
            // An operator holding an UNRELATED permission but not the independent-site advertising one.
            authenticateWith(Set.of("operator"), List.of("advertising:amazon:operate"));

            assertThatThrownBy(() -> proxy.createCampaign(new GoogleAdsCampaignCreateRequest()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(403));

            // The gate ran before the controller body: the write service was never invoked, so no
            // Operation could have been created (Req 7.5).
            verify(service, never()).createCampaign(any());
        }

        @Test
        @DisplayName("adjust is rejected with 403 and no Operation is created")
        void adjustRejectedWhenPermissionMissing() {
            wireController();
            authenticateWith(Set.of("operator"), List.of("advertising:amazon:operate"));

            assertThatThrownBy(() -> proxy.adjust(new GoogleAdsAdjustRequest()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(403));

            verify(service, never()).adjust(any());
        }

        @Test
        @DisplayName("an authorized operator passes the gate and the write service is invoked once")
        void authorizedOperatorPassesGate() {
            wireController();
            // Holds the independent-site advertising Functional_Permission.
            authenticateWith(Set.of("operator"), List.of(INDEPENDENT_ADS_PERMISSION));
            when(service.createCampaign(any()))
                    .thenReturn(OperationResult.builder().operationId(UUID.randomUUID()).build());

            proxy.createCampaign(new GoogleAdsCampaignCreateRequest());

            // The gate permitted the call, so the controller body delegated to the write service.
            verify(service, times(1)).createCampaign(any());
        }

        @Test
        @DisplayName("a super administrator passes the gate without an explicit permission grant")
        void superAdminPassesGate() {
            wireController();
            authenticateWith(Set.of("super_admin"), List.of());
            when(service.adjust(any()))
                    .thenReturn(OperationResult.builder().operationId(UUID.randomUUID()).build());

            proxy.adjust(new GoogleAdsAdjustRequest());

            verify(service, times(1)).adjust(any());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Req 7.6 — pending overlay surfaces the unsettled Google Ads change
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Req 7.6 — pending Google Ads change surfaces through PendingOverlayService")
    class PendingOverlay {

        private static final String ENTITY_TYPE = "campaign";

        private PendingOverlayServiceImpl overlayService;
        private OperationPendingChangeMapper pendingChangeMapper;
        private OperationJsonCodec codec;

        private void wireOverlay() {
            pendingChangeMapper = Mockito.mock(OperationPendingChangeMapper.class);
            codec = new OperationJsonCodec(new ObjectMapper());
            overlayService = new PendingOverlayServiceImpl(pendingChangeMapper, codec);
        }

        private PendingOverlayRow row(String field, String afterValue, SyncState state, LocalDateTime createdAt) {
            PendingOverlayRow r = new PendingOverlayRow();
            r.setField(field);
            r.setAfterValue(codec.toJson(afterValue));
            r.setSyncState(OperationMachineValues.toValue(state));
            r.setCreatedAt(createdAt);
            return r;
        }

        @Test
        @DisplayName("an unsettled budget change surfaces the pending value alongside the confirmed value")
        void unsettledChangeSurfacesPendingAlongsideConfirmed() {
            wireOverlay();
            String campaignId = UUID.randomUUID().toString();

            // A PENDING (Unsettled_State) Google Ads budget change exists for this campaign.
            when(pendingChangeMapper.findOpenUnsettledChanges(any(), any(), any(), any()))
                    .thenReturn(List.of(
                            row("budget", "50.00", SyncState.PENDING, LocalDateTime.of(2024, 1, 1, 0, 0))));

            Map<String, OverlayField> overlay = overlayService.overlayFor(
                    ENTITY_TYPE, campaignId, Map.of("budget", "20.00"));

            OverlayField budget = overlay.get("budget");
            // The platform-confirmed value is passed through untouched (Req 7.6).
            assertThat(budget.getConfirmedValue()).isEqualTo("20.00");
            // The pending change is displayed alongside it, with its Unsettled_State.
            assertThat(budget.hasPending()).isTrue();
            assertThat(budget.getPendingValue()).contains("50.00");
            assertThat(budget.getPendingSyncState()).contains(SyncState.PENDING);
        }

        @Test
        @DisplayName("the latest unsettled change wins when several target the same field")
        void latestUnsettledChangeWins() {
            wireOverlay();
            String campaignId = UUID.randomUUID().toString();

            when(pendingChangeMapper.findOpenUnsettledChanges(any(), any(), any(), any()))
                    .thenReturn(List.of(
                            row("bid", "1.00", SyncState.PENDING, LocalDateTime.of(2024, 1, 1, 0, 0)),
                            row("bid", "2.50", SyncState.SUBMITTED, LocalDateTime.of(2024, 1, 1, 1, 0))));

            Map<String, OverlayField> overlay = overlayService.overlayFor(
                    ENTITY_TYPE, campaignId, Map.of("bid", "0.80"));

            OverlayField bid = overlay.get("bid");
            assertThat(bid.getConfirmedValue()).isEqualTo("0.80");
            assertThat(bid.hasPending()).isTrue();
            // The newer Operation (by created_at) drives the pending view.
            assertThat(bid.getPendingValue()).contains("2.50");
            assertThat(bid.getPendingSyncState()).contains(SyncState.SUBMITTED);
        }

        @Test
        @DisplayName("with no unsettled change only the confirmed value is surfaced")
        void noUnsettledChangeSurfacesConfirmedOnly() {
            wireOverlay();
            String campaignId = UUID.randomUUID().toString();

            when(pendingChangeMapper.findOpenUnsettledChanges(any(), any(), any(), any()))
                    .thenReturn(List.of());

            Map<String, OverlayField> overlay = overlayService.overlayFor(
                    ENTITY_TYPE, campaignId, Map.of("status", "ENABLED"));

            OverlayField status = overlay.get("status");
            assertThat(status.getConfirmedValue()).isEqualTo("ENABLED");
            assertThat(status.hasPending()).isFalse();
            assertThat(status.getPendingValue()).isEmpty();
            assertThat(status.getPendingSyncState()).isEmpty();
        }
    }
}
