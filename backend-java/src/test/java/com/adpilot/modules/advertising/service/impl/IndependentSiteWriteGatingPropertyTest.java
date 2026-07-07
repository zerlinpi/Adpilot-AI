package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.advertising.dto.FulfillmentRequest;
import com.adpilot.modules.advertising.dto.InventoryUpdateRequest;
import com.adpilot.modules.advertising.operation.CreateOperationCommand;
import com.adpilot.modules.advertising.operation.OperationResult;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.advertising.vo.OperationActionVo;
import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the connection-gated, side-effect-free contract of
 * {@link IndependentSiteWriteServiceImpl#updateInventory} and
 * {@link IndependentSiteWriteServiceImpl#markFulfillment}.
 *
 * <p>Feature: multistore-ai-ads-operations, Property 14: 独立站写入受连接状态门控且不可写时无副作用.
 *
 * <p>Validates: Requirements 4.1, 4.3.
 *
 * <p>For any independent-site inventory-update or fulfillment-mark request against an
 * {@code independent_site} store, the system enters the write-back path (creates an
 * Operation + Outbox via {@link OperationService#createOperation}) <em>if and only if</em>
 * the store has a state-{@code connected} Shopify / WooCommerce connection AND the
 * connected platform is write-capable. When there is no valid connection / missing
 * credentials (not authorized) the request is rejected as unauthorized and NO Operation
 * (and therefore no Outbox entry) is created — no write-back is recorded as having
 * happened. The companion "connected but not yet write-capable" case is likewise refused
 * with no Operation created.
 *
 * <p>The store is always modelled as {@code independent_site} and the caller as
 * unauthenticated, so the platform-family / data-scope guards are satisfied trivially and
 * the property isolates the connection-state + capability gate. Each invocation builds
 * fresh mocks so no state leaks across generated examples.
 */
class IndependentSiteWriteGatingPropertyTest {

    /** A {@code platform_connections.status} value that counts as a valid, active connection. */
    private static final String CONNECTED = "connected";

    /** Statuses that are NOT a valid connection — the not-authorized / missing-credentials cases. */
    private static final List<String> NOT_CONNECTED_STATUSES =
            List.of("disconnected", "not_authorized", "unauthorized", "failed", "syncing");

    private static final List<String> SITE_PLATFORMS = List.of("shopify", "woocommerce");

    /** Whether the write path was reached (an Operation was created) for a generated scenario. */
    enum Outcome { WRITE_BACK, NOT_AUTHORIZED, UNSUPPORTED }

    /**
     * Feature: multistore-ai-ads-operations, Property 14: 独立站写入受连接状态门控且不可写时无副作用.
     *
     * <p>Validates: Requirements 4.1, 4.3.
     *
     * <p>Inventory update enters the write-back path iff the store is connected AND
     * write-capable; otherwise it is refused with no Operation/Outbox created.
     */
    @Property(tries = 200)
    void inventoryUpdateIsGatedByConnectionAndHasNoSideEffectWhenNotWritable(
            @ForAll("scenarios") Scenario scenario) {

        Fixture f = new Fixture(scenario);

        UUID productId = UUID.randomUUID();
        InventoryUpdateRequest request = new InventoryUpdateRequest();
        request.setStoreId(f.storeId.toString());
        request.setQuantity(7);

        assertGated(() -> f.service.updateInventory(productId.toString(), request, null), f, scenario);
    }

    /**
     * Feature: multistore-ai-ads-operations, Property 14: 独立站写入受连接状态门控且不可写时无副作用.
     *
     * <p>Validates: Requirements 4.1, 4.3.
     *
     * <p>Fulfillment mark enters the write-back path iff the store is connected AND
     * write-capable; otherwise it is refused with no Operation/Outbox created.
     */
    @Property(tries = 200)
    void markFulfillmentIsGatedByConnectionAndHasNoSideEffectWhenNotWritable(
            @ForAll("scenarios") Scenario scenario) {

        Fixture f = new Fixture(scenario);

        UUID orderId = UUID.randomUUID();
        FulfillmentRequest request = new FulfillmentRequest();
        request.setStoreId(f.storeId.toString());
        request.setTrackingNumber("TRACK-123");

        assertGated(() -> f.service.markFulfillment(orderId.toString(), request, null), f, scenario);
    }

    /**
     * Run the gated call and assert the iff-contract: an Operation is created exactly
     * when the store is connected AND write-capable; in every refusal case the
     * appropriate unauthorized/unsupported error is thrown and {@code createOperation}
     * is never invoked (no Operation, hence no Outbox entry — no write-back recorded).
     */
    private void assertGated(ThrowingCall call, Fixture f, Scenario scenario) {
        Outcome expected = scenario.expectedOutcome();
        switch (expected) {
            case WRITE_BACK -> {
                OperationActionVo vo = (OperationActionVo) call.invokeUnchecked();
                assertThat(vo).isNotNull();
                assertThat(vo.getOperationId()).isEqualTo(f.createdOperationId.toString());
                // The write-back path was entered exactly once.
                verify(f.operationService).createOperation(any(CreateOperationCommand.class));
            }
            case NOT_AUTHORIZED -> {
                assertThatThrownBy(call::invokeUnchecked)
                        .isInstanceOf(BusinessException.class)
                        .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(403));
                // No valid connection / missing credentials → no side effect at all.
                verify(f.operationService, never()).createOperation(any());
            }
            case UNSUPPORTED -> {
                assertThatThrownBy(call::invokeUnchecked)
                        .isInstanceOf(BusinessException.class)
                        .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(409));
                // Capability missing → still no Operation/Outbox created.
                verify(f.operationService, never()).createOperation(any());
            }
            default -> throw new IllegalStateException("Unhandled outcome: " + expected);
        }
    }

    // --- fixture -----------------------------------------------------------

    /** A fully wired service-under-test with mocks configured for one generated scenario. */
    private static final class Fixture {
        final IndependentSiteWriteServiceImpl service;
        final OperationService operationService;
        final UUID storeId = UUID.randomUUID();
        final UUID createdOperationId = UUID.randomUUID();

        Fixture(Scenario scenario) {
            operationService = Mockito.mock(OperationService.class);
            WriteCapabilityService writeCapabilityService = Mockito.mock(WriteCapabilityService.class);
            PlatformConnectionMapper connectionMapper = Mockito.mock(PlatformConnectionMapper.class);
            DataScopeService dataScopeService = Mockito.mock(DataScopeService.class);
            StoreMapper storeMapper = Mockito.mock(StoreMapper.class);

            // The target store is always an independent_site store so the family / scope
            // guard is satisfied and the test isolates the connection-state + capability gate.
            StoreEntity store = StoreEntity.builder()
                    .id(storeId)
                    .orgId(UUID.randomUUID())
                    .name("indie-store")
                    .marketplaceId(UUID.randomUUID())
                    .platformFamily(PlatformFamily.INDEPENDENT_SITE.getCode())
                    .build();
            when(storeMapper.selectById(storeId)).thenReturn(store);

            // Connection-state gate: return the generated set of connections for the store.
            when(connectionMapper.selectList(any())).thenReturn(scenario.connections(storeId));

            // Capability gate.
            when(writeCapabilityService.isWriteCapable(storeId)).thenReturn(scenario.writeCapable);

            // Only the write-back path reaches createOperation; stub it for that case.
            OperationResult result = OperationResult.builder()
                    .operationId(createdOperationId)
                    .storeId(storeId)
                    .build();
            Mockito.lenient().when(operationService.createOperation(any())).thenReturn(result);

            service = new IndependentSiteWriteServiceImpl(
                    operationService,
                    writeCapabilityService,
                    connectionMapper,
                    dataScopeService,
                    storeMapper,
                    Collections.<PlatformWriteConnector>emptyList());
        }
    }

    @FunctionalInterface
    private interface ThrowingCall {
        Object invokeUnchecked();
    }

    // --- scenario model ----------------------------------------------------

    /**
     * One generated scenario: the set of connection statuses present for the store, plus
     * whether the connected platform is write-capable.
     */
    static final class Scenario {
        final List<ConnRow> rows;
        final boolean writeCapable;

        Scenario(List<ConnRow> rows, boolean writeCapable) {
            this.rows = rows;
            this.writeCapable = writeCapable;
        }

        boolean hasConnected() {
            return rows.stream().anyMatch(r -> CONNECTED.equalsIgnoreCase(r.status));
        }

        Outcome expectedOutcome() {
            if (!hasConnected()) {
                return Outcome.NOT_AUTHORIZED;
            }
            return writeCapable ? Outcome.WRITE_BACK : Outcome.UNSUPPORTED;
        }

        List<PlatformConnectionEntity> connections(UUID storeId) {
            List<PlatformConnectionEntity> list = new ArrayList<>(rows.size());
            for (ConnRow row : rows) {
                list.add(PlatformConnectionEntity.builder()
                        .id(UUID.randomUUID())
                        .storeId(storeId)
                        .platform(row.platform)
                        .status(row.status)
                        .build());
            }
            return list;
        }

        @Override
        public String toString() {
            return "Scenario{rows=" + rows + ", writeCapable=" + writeCapable
                    + ", expected=" + expectedOutcome() + "}";
        }
    }

    static final class ConnRow {
        final String platform;
        final String status;

        ConnRow(String platform, String status) {
            this.platform = platform;
            this.status = status;
        }

        @Override
        public String toString() {
            return platform + "=" + status;
        }
    }

    // --- generators --------------------------------------------------------

    /**
     * Generate scenarios spanning the full input space of the gate:
     * <ul>
     *   <li>no connections at all (not authorized),</li>
     *   <li>only not-connected statuses (not authorized),</li>
     *   <li>at least one connected status (write-back when capable, unsupported otherwise),</li>
     * </ul>
     * crossed with the write-capability flag.
     */
    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<List<ConnRow>> rows = connectionRows().list().ofMinSize(0).ofMaxSize(4);
        Arbitrary<Boolean> capable = Arbitraries.of(true, false);
        return Combinators.combine(rows, capable).as(Scenario::new);
    }

    private Arbitrary<ConnRow> connectionRows() {
        Arbitrary<String> platform = Arbitraries.of(SITE_PLATFORMS.toArray(new String[0]));
        List<String> statuses = new ArrayList<>(NOT_CONNECTED_STATUSES);
        statuses.add(CONNECTED);
        statuses.add(CONNECTED.toUpperCase()); // exercise case-insensitive match
        Arbitrary<String> status = Arbitraries.of(statuses.toArray(new String[0]));
        return Combinators.combine(platform, status).as(ConnRow::new);
    }
}
