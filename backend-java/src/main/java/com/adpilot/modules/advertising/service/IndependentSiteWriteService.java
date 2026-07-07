package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.dto.FulfillmentRequest;
import com.adpilot.modules.advertising.dto.InventoryUpdateRequest;
import com.adpilot.modules.advertising.vo.IndependentSiteConnectionStateVo;
import com.adpilot.modules.advertising.vo.OperationActionVo;

import java.util.List;

/**
 * Independent-site inventory / fulfillment write-back orchestration (Req 4).
 *
 * <p>Backs {@code POST /api/independent-site/products/{productId}/inventory} and
 * {@code POST /api/independent-site/orders/{orderId}/fulfillment}. Every write is
 * gated and, when allowed, routed through the Operation-Outbox so the external
 * Shopify / WooCommerce platform is never called on the request thread (Req 4.2).
 *
 * <h2>Connection gating (Req 4.1, 4.3)</h2>
 * A write is allowed only when the target store has a state-{@code connected}
 * Shopify or WooCommerce connection AND that platform is actually write-capable
 * (a {@link com.adpilot.modules.apisync.connector.PlatformWriteConnector} bean is
 * registered for it), per {@link com.adpilot.modules.advertising.platform.WriteCapabilityService}.
 * When the store has no valid connection or lacks credentials the request is
 * refused as <em>not authorized</em> (HTTP 403) and <strong>no</strong> Operation
 * or Outbox entry is created and no write-back is recorded as having happened
 * (Req 4.3). When a connection exists but the platform's write capability is not
 * yet available, the request is refused as <em>unsupported</em> (HTTP 409) rather
 * than silently failing (Req 4.7).
 *
 * <h2>Asynchronous write-back (Req 4.2)</h2>
 * When the write is allowed, the service builds a {@code platform_mutation}
 * {@link com.adpilot.modules.advertising.operation.CreateOperationCommand} and
 * persists it through
 * {@link com.adpilot.modules.advertising.operation.OperationService#createOperation},
 * which writes the Operation plus a single Outbox entry in one transaction with no
 * platform call. The {@code OutboxWorker} discovers the connector and submits the
 * change asynchronously.
 *
 * <h2>Failure handling (Req 4.5)</h2>
 * When the asynchronous platform write-back fails, the existing Operation state
 * machine sets the Operation to {@code failed} and retains the platform's readable
 * failure reason, so the operator can retry or investigate. That behaviour is
 * reused unchanged; this service only enqueues the write.
 */
public interface IndependentSiteWriteService {

    /**
     * Enqueue an inventory update for an independent-site product (Req 4.1, 4.2).
     *
     * @param productId the independent-site product whose inventory is set (path id)
     * @param request   the validated inventory payload (carries the owning store)
     * @param userId    the authenticated user submitting the request (may be {@code null})
     * @return the created Operation's id, the {@code inventory_update} action, and its sync state
     * @throws com.adpilot.common.exception.BusinessException 403 when the store has no valid
     *         connection / credentials (Req 4.3), or 409 when the platform write capability is
     *         unavailable (Req 4.7)
     */
    OperationActionVo updateInventory(String productId, InventoryUpdateRequest request, String userId);

    /**
     * Enqueue a fulfillment / shipment mark for an independent-site order (Req 4.1, 4.2).
     *
     * @param orderId the independent-site order being marked fulfilled (path id)
     * @param request the validated fulfillment payload (carries the owning store)
     * @param userId  the authenticated user submitting the request (may be {@code null})
     * @return the created Operation's id, the {@code fulfillment} action, and its sync state
     * @throws com.adpilot.common.exception.BusinessException 403 when the store has no valid
     *         connection / credentials (Req 4.3), or 409 when the platform write capability is
     *         unavailable (Req 4.7)
     */
    OperationActionVo markFulfillment(String orderId, FulfillmentRequest request, String userId);

    /**
     * Return the connection state and write-capability flags for the caller's
     * independent-site stores (Req 4.4, 4.7).
     *
     * <p>For each in-scope {@code shopify} / {@code woocommerce}
     * {@link com.adpilot.modules.apisync.entity.PlatformConnectionEntity}, the result
     * carries the connection state (Connection_State vocabulary:
     * {@code not_authorized} | {@code syncing} | {@code failed} | {@code connected})
     * and two capability flags. A capability is reported as supported only when a
     * {@link com.adpilot.modules.apisync.connector.PlatformWriteConnector} bean is
     * registered for the connection's platform; an unregistered connector or a
     * missing capability is reported as unsupported ({@code false}), so the frontend
     * can honestly mark the entry "暂不支持" instead of offering a silently failing
     * action (Req 4.7).
     *
     * <p>Reads are restricted to the caller's data scope via
     * {@link com.adpilot.common.security.DataScopeService}: a non-super-admin only
     * sees connection state for stores within their {@code Store_Group_Scope}.
     *
     * @param storeId optional store id filter; when {@code null}/blank, every in-scope
     *                independent-site store is returned
     * @return the per-store/platform connection states and capability flags (never {@code null})
     */
    List<IndependentSiteConnectionStateVo> getConnectionStates(String storeId);
}
