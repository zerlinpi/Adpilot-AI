package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.dto.FulfillmentRequest;
import com.adpilot.modules.advertising.dto.InventoryUpdateRequest;
import com.adpilot.modules.advertising.operation.CreateOperationCommand;
import com.adpilot.modules.advertising.operation.OperationResult;
import com.adpilot.modules.advertising.operation.OperationScope;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.OperationSource;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.advertising.service.TikTokWriteService;
import com.adpilot.modules.advertising.support.StoreScopeRef;
import com.adpilot.modules.advertising.vo.IndependentSiteConnectionStateVo;
import com.adpilot.modules.advertising.vo.OperationActionVo;
import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.ConnectionStatus;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Default {@link TikTokWriteService}.
 *
 * <p>The exact TikTok analogue of {@link IndependentSiteWriteServiceImpl}: it
 * implements the connection-gated, Outbox-asynchronous write-back contract for
 * TikTok Shop inventory updates and fulfillment marks (Req 4.1, 4.2, 4.3, 4.5).
 * The flow is identical for both write kinds and is captured by
 * {@link #enqueueWrite}: gate the store on its {@code tiktok_shop} connection and
 * write capability, and — only when the store is truly write-capable — build a
 * {@code platform_mutation} {@link CreateOperationCommand} and persist it through
 * {@link OperationService#createOperation}, which writes the Operation and a single
 * Outbox entry in one transaction with no synchronous platform call (Req 4.2).
 *
 * <p>The gate distinguishes the two honest "cannot write" cases (Req 4.3, 4.7):
 * <ul>
 *   <li><b>not authorized (HTTP 403)</b> — the store has no state-{@code connected}
 *       {@code tiktok_shop} connection (no valid connection / missing credentials).
 *       Nothing is persisted and no write-back is recorded.</li>
 *   <li><b>unsupported (HTTP 409)</b> — a connection exists but the platform's
 *       write capability is not yet available (no
 *       {@link com.adpilot.modules.apisync.connector.PlatformWriteConnector} bean
 *       registered for it), so the request is refused rather than silently failing
 *       (Req 4.7).</li>
 * </ul>
 *
 * <p>Failure of the asynchronous platform write-back (Req 4.5) is handled by the
 * existing Operation state machine, which sets the Operation to {@code failed} and
 * retains the readable failure reason; this service only enqueues the write.
 */
@Slf4j
@Service
public class TikTokWriteServiceImpl implements TikTokWriteService {

    /** TikTok platforms that support inventory / fulfillment write-back. */
    static final List<String> TIKTOK_PLATFORMS = List.of("tiktok_shop");

    /** The single {@code platform_connections.status} value treated as a valid, active connection. */
    static final String STATUS_CONNECTED = ConnectionStatus.CONNECTED;

    /** Operation entity types and fields routed to the TikTok write connector. */
    private static final String ENTITY_PRODUCT = "product";
    private static final String ENTITY_ORDER = "order";
    private static final String FIELD_INVENTORY = "inventory";
    private static final String FIELD_FULFILLMENT = "fulfillment";

    /** Functional permissions re-checked server-side by the Operation pipeline (defense in depth). */
    private static final String PERMISSION_PRODUCT_MANAGE = "product:manage";
    private static final String PERMISSION_ORDER_MANAGE = "order:manage";

    /** TikTok write-back is confined to the {@code tiktok} platform family (Req 4.6). */
    private static final PlatformFamily REQUIRED_FAMILY = PlatformFamily.TIKTOK;

    private final OperationService operationService;
    private final WriteCapabilityService writeCapabilityService;
    private final PlatformConnectionMapper platformConnectionMapper;
    private final DataScopeService dataScopeService;
    private final StoreMapper storeMapper;

    /** Platform keys with a registered {@link PlatformWriteConnector} bean — the capability signal (Req 4.7). */
    private final Set<String> registeredWritePlatforms;

    public TikTokWriteServiceImpl(OperationService operationService,
                                  WriteCapabilityService writeCapabilityService,
                                  PlatformConnectionMapper platformConnectionMapper,
                                  DataScopeService dataScopeService,
                                  StoreMapper storeMapper,
                                  List<PlatformWriteConnector> writeConnectorBeans) {
        this.operationService = operationService;
        this.writeCapabilityService = writeCapabilityService;
        this.platformConnectionMapper = platformConnectionMapper;
        this.dataScopeService = dataScopeService;
        this.storeMapper = storeMapper;
        Set<String> platforms = new HashSet<>();
        if (writeConnectorBeans != null) {
            for (PlatformWriteConnector connector : writeConnectorBeans) {
                if (connector != null && connector.platform() != null) {
                    platforms.add(connector.platform());
                }
            }
        }
        this.registeredWritePlatforms = platforms;
    }

    @Override
    public OperationActionVo updateInventory(String productId, InventoryUpdateRequest request, String userId) {
        if (request == null) {
            throw badRequest("Inventory update request body is required");
        }
        UUID storeId = requireUuid(request.getStoreId(), "storeId");
        UUID entityId = requireUuid(productId, "productId");

        assertWriteScopeAndFamily(storeId);

        return enqueueWrite(storeId, ENTITY_PRODUCT, entityId, FIELD_INVENTORY,
                request.getQuantity(), PERMISSION_PRODUCT_MANAGE,
                "inventory_update", FIELD_INVENTORY + ":" + entityId);
    }

    @Override
    public OperationActionVo markFulfillment(String orderId, FulfillmentRequest request, String userId) {
        if (request == null) {
            throw badRequest("Fulfillment request body is required");
        }
        UUID storeId = requireUuid(request.getStoreId(), "storeId");
        UUID entityId = requireUuid(orderId, "orderId");

        assertWriteScopeAndFamily(storeId);

        return enqueueWrite(storeId, ENTITY_ORDER, entityId, FIELD_FULFILLMENT,
                request.getTrackingNumber(), PERMISSION_ORDER_MANAGE,
                "fulfillment", FIELD_FULFILLMENT + ":" + entityId);
    }

    @Override
    public List<IndependentSiteConnectionStateVo> getConnectionStates(String storeId) {
        // Restrict to the caller's data scope so a non-super-admin only sees connection
        // state for stores within their Store_Group_Scope (Req 4.4, read isolation).
        QueryWrapper<PlatformConnectionEntity> wrapper = new QueryWrapper<>();
        wrapper.in("platform", TIKTOK_PLATFORMS);
        UUID parsedStoreId = optionalUuid(storeId, "storeId");
        if (parsedStoreId != null) {
            wrapper.eq("store_id", parsedStoreId);
        }
        dataScopeService.applyScope(wrapper, com.adpilot.common.security.ScopeTarget.store("store_id"), scopeUser());

        List<PlatformConnectionEntity> connections = platformConnectionMapper.selectList(wrapper);
        List<IndependentSiteConnectionStateVo> states = new ArrayList<>(connections.size());
        for (PlatformConnectionEntity connection : connections) {
            if (connection == null) {
                continue;
            }
            // Capability flags derive from whether a write connector bean is registered for
            // the platform; an unregistered connector / missing capability is unsupported (Req 4.7).
            boolean writeSupported = connection.getPlatform() != null
                    && registeredWritePlatforms.contains(connection.getPlatform());
            states.add(IndependentSiteConnectionStateVo.builder()
                    .storeId(connection.getStoreId() != null ? connection.getStoreId().toString() : null)
                    .platform(connection.getPlatform())
                    .connectionState(mapConnectionState(connection.getStatus()))
                    .inventoryWriteSupported(writeSupported)
                    .fulfillmentWriteSupported(writeSupported)
                    .lastSuccessAt(connection.getLastSyncAt())
                    .build());
        }
        return states;
    }

    /**
     * Enforce the store-scope and platform-family isolation that precedes every
     * TikTok write (Req 4.6). The caller must be able to write the target store
     * within their data scope, and the target store's {@code platform_family} must
     * be {@code tiktok}; a store outside scope or of another family is rejected
     * with HTTP 403 before any Operation/Outbox is created.
     */
    private void assertWriteScopeAndFamily(UUID storeId) {
        CurrentUser user = scopeUser();
        if (user != null) {
            // Store-scope isolation: reflection reads the storeId off the holder (Req 4.6).
            dataScopeService.assertCanWrite(StoreScopeRef.of(storeId), user);
        }
        // Platform-family isolation: the target store must belong to the tiktok family.
        StoreEntity store = storeId != null ? storeMapper.selectById(storeId) : null;
        if (store == null || !isTikTokFamily(store.getPlatformFamily())) {
            log.info("TikTok write refused (cross-family): store={} family={} expected={}",
                    storeId, store != null ? store.getPlatformFamily() : null, REQUIRED_FAMILY.getCode());
            throw forbiddenFamily(
                    "Store " + storeId + " is not a TikTok store; "
                            + "TikTok write-back is confined to the tiktok platform family");
        }
    }

    private static boolean isTikTokFamily(String platformFamily) {
        if (platformFamily == null || platformFamily.isBlank()) {
            return false;
        }
        try {
            return PlatformFamily.fromCode(platformFamily) == REQUIRED_FAMILY;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Map a {@code platform_connections.status} to the Connection_State vocabulary used
     * by {@link IndependentSiteConnectionStateVo}: {@code connected} | {@code syncing} |
     * {@code failed} | {@code not_authorized}. An absent/disconnected status is reported
     * as {@code not_authorized} (no valid connection / credentials, Req 4.4).
     */
    private static String mapConnectionState(String status) {
        if (status == null || status.isBlank()) {
            return "not_authorized";
        }
        return switch (status.trim().toLowerCase()) {
            case "connected" -> "connected";
            case "syncing" -> "syncing";
            case "failed" -> "failed";
            case "disconnected", "not_authorized", "unauthorized" -> "not_authorized";
            default -> status.trim().toLowerCase();
        };
    }

    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    /**
     * Gate the store and, when write-capable, enqueue a {@code platform_mutation}
     * Operation (Req 4.1, 4.2, 4.3, 4.7).
     *
     * <p>The store is refused with HTTP 403 when it has no state-{@code connected}
     * {@code tiktok_shop} connection (Req 4.3) and with HTTP 409 when a connection
     * exists but the platform is not yet write-capable (Req 4.7). In both refusal
     * cases nothing is persisted — {@link OperationService#createOperation} is never
     * reached, so no Operation or Outbox entry is created (Req 4.3).
     */
    private OperationActionVo enqueueWrite(UUID storeId, String entityType, UUID entityId, String field,
                                           Object afterValue, String requiredPermission,
                                           String action, String logicalIdempotencyKey) {
        // Req 4.3 — refuse when the store has no valid, connected tiktok_shop connection.
        boolean hasConnectedTikTok = hasConnectedTikTokConnection(storeId);
        if (!hasConnectedTikTok) {
            log.info("TikTok write refused (not authorized): store={} action={} — no connected "
                    + "tiktok_shop connection", storeId, action);
            throw notAuthorized(
                    "Store " + storeId + " has no connected TikTok Shop connection; "
                            + "connect the store before writing back");
        }

        // Req 4.7 — a connection exists but the platform's write capability is not available.
        if (!writeCapabilityService.isWriteCapable(storeId)) {
            log.info("TikTok write refused (unsupported): store={} action={} — write capability "
                    + "unavailable for the connected platform", storeId, action);
            throw unsupported(
                    "Write-back is not yet supported for this store's connected platform");
        }

        // Write-capable: enqueue via Operation + Outbox; the platform is called only
        // asynchronously by the OutboxWorker, never on this request thread (Req 4.2).
        CreateOperationCommand command = CreateOperationCommand.builder()
                .storeId(storeId)
                .operationSource(OperationSource.MANUAL)
                .operationScope(OperationScope.PLATFORM_MUTATION)
                .entityType(entityType)
                .entityId(entityId)
                .field(field)
                .afterValue(afterValue)
                .requiredPermission(requiredPermission)
                .logicalIdempotencyKey(logicalIdempotencyKey)
                .build();

        OperationResult result = operationService.createOperation(command);
        log.info("TikTok {} Operation created: store={} entity={}/{} operation={} syncState={}",
                action, storeId, entityType, entityId, result.getOperationId(), result.getSyncState());

        return OperationActionVo.builder()
                .operationId(result.getOperationId() != null ? result.getOperationId().toString() : null)
                .action(action)
                .syncState(result.getSyncState() != null
                        ? result.getSyncState().name().toLowerCase()
                        : null)
                .build();
    }

    /**
     * Whether the store has at least one state-{@code connected} {@code tiktok_shop}
     * connection — the "valid connection / has credentials" precondition of Req 4.1 / 4.3.
     */
    private boolean hasConnectedTikTokConnection(UUID storeId) {
        if (storeId == null) {
            return false;
        }
        List<PlatformConnectionEntity> connections = platformConnectionMapper.selectList(
                new LambdaQueryWrapper<PlatformConnectionEntity>()
                        .eq(PlatformConnectionEntity::getStoreId, storeId)
                        .in(PlatformConnectionEntity::getPlatform, TIKTOK_PLATFORMS));
        return connections.stream().anyMatch(TikTokWriteServiceImpl::isConnected);
    }

    private static boolean isConnected(PlatformConnectionEntity connection) {
        return connection != null && STATUS_CONNECTED.equalsIgnoreCase(connection.getStatus());
    }

    private static UUID requireUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw badRequest(field + " is required");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw badRequest("Invalid " + field + ": " + value);
        }
    }

    /** Parse an optional UUID filter; {@code null}/blank yields {@code null} (no filter). */
    private static UUID optionalUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw badRequest("Invalid " + field + ": " + value);
        }
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(400, "INVALID_TIKTOK_WRITE", message);
    }

    /** Req 4.3 — no valid connection / missing credentials → not authorized (HTTP 403). */
    private static BusinessException notAuthorized(String message) {
        return new BusinessException(403, "TIKTOK_NOT_AUTHORIZED", message);
    }

    /** Req 4.7 — platform write capability not yet available → unsupported (HTTP 409). */
    private static BusinessException unsupported(String message) {
        return new BusinessException(409, "TIKTOK_WRITE_UNSUPPORTED", message);
    }

    /** Req 4.6 — target store outside scope or of another platform family → forbidden (HTTP 403). */
    private static BusinessException forbiddenFamily(String message) {
        return new BusinessException(403, "TIKTOK_CROSS_FAMILY", message);
    }
}
