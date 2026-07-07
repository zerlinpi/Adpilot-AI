package com.adpilot.modules.apisync.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.operation.CreateOperationCommand;
import com.adpilot.modules.advertising.operation.OperationResult;
import com.adpilot.modules.advertising.operation.OperationScope;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.OperationSource;
import com.adpilot.modules.apisync.dto.GoogleAdsAdjustRequest;
import com.adpilot.modules.apisync.dto.GoogleAdsCampaignCreateRequest;
import com.adpilot.modules.apisync.service.GoogleAdsManualOperationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;

/**
 * Default {@link GoogleAdsManualOperationService}.
 *
 * <p>Builds a {@link CreateOperationCommand} for each manual Google Ads action and
 * delegates to {@link OperationService#createOperation(CreateOperationCommand)},
 * which runs the generic pipeline (permission + data-scope check, idempotency,
 * write-capability resolution) and — for a write-capable Store whose active
 * connection is {@code google_ads} — persists the Operation plus a single
 * {@code operation_outbox} entry on the {@code google_ads} route. The
 * {@code OutboxWorker} discovers the
 * {@link com.adpilot.modules.apisync.connector.GoogleAdsWriteConnector} by that
 * platform value and submits the change asynchronously (Req 7.1, 7.2, 7.3, 7.4).</p>
 *
 * <p>The command carries the independent-site advertising Functional_Permission as
 * its {@code requiredPermission} so the pipeline re-checks authorization
 * server-side (defense in depth) even though the controller already gates the
 * endpoint with the same permission (Req 7.5).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleAdsManualOperationServiceImpl implements GoogleAdsManualOperationService {

    /**
     * The independent-site advertising Functional_Permission required to create or
     * adjust Google Ads campaigns (platform-workspace-rbac Req 7.5, 14.2). Distinct
     * from the Amazon advertising permission so the two families are independently
     * grantable.
     */
    public static final String INDEPENDENT_ADS_PERMISSION = "advertising:independent_site:operate";

    /** The Operation entity type for a campaign-scoped change (create, budget, status). */
    private static final String ENTITY_CAMPAIGN = "campaign";

    /** Connector change-type tokens (mirrors {@code GoogleAdsWriteConnector}). */
    private static final String CHANGE_BID = "bid";
    private static final String CHANGE_BUDGET = "budget";
    private static final String CHANGE_STATUS = "status";

    private final OperationService operationService;

    @Override
    public OperationResult createCampaign(GoogleAdsCampaignCreateRequest request) {
        if (request == null) {
            throw badRequest("Google Ads 创建广告系列请求不能为空");
        }
        UUID storeId = requireUuid(request.getStoreId(), "storeId");
        String name = request.getName();
        if (name == null || name.isBlank()) {
            throw badRequest("广告系列名称不能为空");
        }
        if (name.length() > 255) {
            throw badRequest("广告系列名称长度不能超过 255 个字符");
        }
        // A campaign create is a whole-object create: no single writable field. The internal
        // entity id is supplied by the caller or minted here so the Operation has a stable target.
        UUID campaignId = optionalUuid(request.getCampaignId(), "campaignId");
        if (campaignId == null) {
            campaignId = UUID.randomUUID();
        }

        CreateOperationCommand command = CreateOperationCommand.builder()
                .storeId(storeId)
                .operationSource(OperationSource.CREATION)
                .operationScope(OperationScope.PLATFORM_MUTATION)
                .entityType(ENTITY_CAMPAIGN)
                .entityId(campaignId)
                .field(null)
                .afterValue(name)
                .requiredPermission(INDEPENDENT_ADS_PERMISSION)
                .logicalIdempotencyKey(request.getLogicalIdempotencyKey())
                .build();

        OperationResult result = operationService.createOperation(command);
        log.info("Google Ads campaign create Operation created: store={} campaign={} operation={}",
                storeId, campaignId, result.getOperationId());
        return result;
    }

    @Override
    public OperationResult adjust(GoogleAdsAdjustRequest request) {
        if (request == null) {
            throw badRequest("Google Ads 调整请求不能为空");
        }
        UUID storeId = requireUuid(request.getStoreId(), "storeId");
        UUID entityId = requireUuid(request.getEntityId(), "entityId");
        String afterValue = request.getAfterValue();
        if (afterValue == null || afterValue.isBlank()) {
            throw badRequest("调整的目标值（afterValue）不能为空");
        }

        // Map the requested change type to the connector's field/change routing:
        // bid -> ad-group criterion bid, budget -> campaign budget, status -> campaign status.
        String field = resolveField(request.getChangeType());
        String entityType = (request.getEntityType() == null || request.getEntityType().isBlank())
                ? ENTITY_CAMPAIGN
                : request.getEntityType().trim();

        CreateOperationCommand command = CreateOperationCommand.builder()
                .storeId(storeId)
                .operationSource(OperationSource.MANUAL)
                .operationScope(OperationScope.PLATFORM_MUTATION)
                .entityType(entityType)
                .entityId(entityId)
                .field(field)
                .beforeValue(request.getBeforeValue())
                .afterValue(afterValue)
                .requiredPermission(INDEPENDENT_ADS_PERMISSION)
                .logicalIdempotencyKey(request.getLogicalIdempotencyKey())
                .expectedVersion(request.getExpectedVersion())
                .build();

        OperationResult result = operationService.createOperation(command);
        log.info("Google Ads {} change Operation created: store={} entity={}/{} operation={}",
                request.getChangeType(), storeId, entityType, entityId, result.getOperationId());
        return result;
    }

    /**
     * Resolve the connector change field from the requested change type. The
     * GoogleAdsWriteConnector routes by this field: {@code bid} and {@code budget}
     * pass through unchanged; {@code status} maps to the connector's {@code state}
     * change so the campaign status mutate is selected.
     */
    private String resolveField(String changeType) {
        if (changeType == null || changeType.isBlank()) {
            throw badRequest("changeType 不能为空，应为 bid / budget / status 之一");
        }
        return switch (changeType.trim().toLowerCase(Locale.ROOT)) {
            case CHANGE_BID -> "bid";
            case CHANGE_BUDGET -> "budget";
            case CHANGE_STATUS -> "state";
            default -> throw badRequest("不支持的 changeType：" + changeType + "（应为 bid / budget / status）");
        };
    }

    private static UUID requireUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw badRequest(field + " 不能为空");
        }
        return parse(value, field);
    }

    private static UUID optionalUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parse(value, field);
    }

    private static UUID parse(String value, String field) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw badRequest("无效的 " + field + "：" + value);
        }
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(400, "INVALID_GOOGLE_ADS_OPERATION", message);
    }
}
