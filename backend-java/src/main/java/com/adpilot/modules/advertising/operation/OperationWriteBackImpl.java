package com.adpilot.modules.advertising.operation;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.ConnectionStatus;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.PlatformChange;
import com.adpilot.modules.apisync.model.PlatformWriteResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link OperationWriteBack}.
 *
 * <p>{@link #applyOperation(UUID)} is the generic, source-agnostic submission path for a
 * {@code platform_mutation} Operation (Req 2.3, 9.1, 53.5). It:</p>
 *
 * <ol>
 *   <li><b>Routes ONLY {@code platform_mutation}</b> — a {@code local_configuration} Operation has no
 *       platform side and is rejected (Req 2.3).</li>
 *   <li><b>Is idempotent on state</b> — only an Operation that is still {@code pending} is submitted;
 *       an Operation that has already left {@code pending} returns its current result unchanged so a
 *       duplicate apply never produces a duplicate platform submission (Req 5.7).</li>
 *   <li><b>Resolves the Active_Store's {@code PlatformConnection}</b> (the valid, active connection)
 *       and the platform's {@link PlatformWriteConnector} (one per platform, injected once).</li>
 *   <li><b>Builds a {@link PlatformChange} from the pending value</b> — the Operation's
 *       {@code after_value} becomes the recommended value, the {@code before_value} the current
 *       value, and the change carries the Operation's {@code submissionIdempotencyKey}.</li>
 *   <li><b>Submits OUTSIDE any DB transaction</b> (Req 6.3) and routes the resulting Sync_State
 *       change through {@link OperationService#transition} so the {@link OperationStateMachine} stays
 *       the sole authority: {@code pending → submitted} on the attempt, then {@code submitted →
 *       failed} on a platform rejection or transport error, with the platform reference (Req 55.7)
 *       and/or failure reason recorded on the Operation_Record.</li>
 * </ol>
 *
 * <p>Validates: Requirements 2.3, 9.1, 53.5.</p>
 */
@Slf4j
@Service
public class OperationWriteBackImpl implements OperationWriteBack {

    /** The connection status treated as a valid active connection (mirrors WriteCapabilityServiceImpl). */
    private static final String STATUS_CONNECTED = ConnectionStatus.CONNECTED;

    /** platform_mutation scope marker, matched against the Operation's stored scope value. */
    private static final String SCOPE_PLATFORM_MUTATION =
            OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION);

    private final OperationMapper operationMapper;
    private final OperationService operationService;
    private final IdempotencyService idempotencyService;
    private final PlatformConnectionMapper platformConnectionMapper;
    private final OperationJsonCodec jsonCodec;
    private final ObjectMapper objectMapper;
    private final CryptoUtil cryptoUtil;

    /** platform key -> write connector, built once from all injected connector beans. */
    private final Map<String, PlatformWriteConnector> writeConnectors = new ConcurrentHashMap<>();

    public OperationWriteBackImpl(OperationMapper operationMapper,
                                  OperationService operationService,
                                  IdempotencyService idempotencyService,
                                  PlatformConnectionMapper platformConnectionMapper,
                                  OperationJsonCodec jsonCodec,
                                  ObjectMapper objectMapper,
                                  CryptoUtil cryptoUtil,
                                  List<PlatformWriteConnector> writeConnectorBeans) {
        this.operationMapper = operationMapper;
        this.operationService = operationService;
        this.idempotencyService = idempotencyService;
        this.platformConnectionMapper = platformConnectionMapper;
        this.jsonCodec = jsonCodec;
        this.objectMapper = objectMapper;
        this.cryptoUtil = cryptoUtil;
        for (PlatformWriteConnector connector : writeConnectorBeans) {
            this.writeConnectors.put(connector.platform(), connector);
        }
    }

    @Override
    public OperationResult applyOperation(UUID operationId) {
        if (operationId == null) {
            throw new BusinessException(400, "INVALID_OPERATION", "operationId is required");
        }

        OperationEntity operation = operationMapper.selectById(operationId);
        if (operation == null) {
            throw new BusinessException(404, "OPERATION_NOT_FOUND",
                    "未找到要提交的操作记录：" + operationId);
        }

        // 1. Route ONLY platform_mutation (Req 2.3). A local_configuration has no platform side.
        if (!SCOPE_PLATFORM_MUTATION.equals(operation.getOperationScope())) {
            throw new BusinessException(409, "NOT_PLATFORM_MUTATION",
                    "Operation_Write_Back 仅处理 platform_mutation 操作：" + operationId);
        }

        // 2. Idempotent on state (Req 5.7): only a still-pending Operation is submitted. An Operation
        // that has already left pending (submitted/effective/failed/local-only/...) is a no-op so a
        // duplicate apply never produces a duplicate platform submission.
        SyncState current = OperationMachineValues.toSyncState(operation.getSyncState());
        if (current != SyncState.PENDING) {
            log.debug("applyOperation no-op: Operation {} is not pending (state={})", operationId, current);
            return OperationResult.from(operation, false);
        }

        // 3. Resolve the Active_Store's valid active PlatformConnection and the platform's connector.
        PlatformConnectionEntity connection = resolveActiveConnection(operation.getStoreId());
        PlatformWriteConnector connector = writeConnectors.get(connection.getPlatform());
        if (connector == null) {
            // The store should have been resolved to local-only at creation if no connector exists;
            // reaching here means a registered platform lost its connector. Leave the Operation
            // pending (no state change) and surface a typed error so the Outbox worker can retry.
            throw new BusinessException(409, "WRITE_CONNECTOR_UNAVAILABLE",
                    "平台 '" + connection.getPlatform() + "' 未注册写连接器，无法提交操作：" + operationId);
        }

        // 4. Ensure the attempt carries a submissionIdempotencyKey (Req 5.2/5.7) and persist it before
        // submitting so the key is durable even if the process dies mid-submission.
        String submissionKey = operation.getSubmissionIdempotencyKey();
        if (submissionKey == null || submissionKey.isBlank()) {
            submissionKey = idempotencyService.newSubmissionIdempotencyKey();
            final String key = submissionKey;
            operationMapper.update(null, new LambdaUpdateWrapper<OperationEntity>()
                    .eq(OperationEntity::getId, operationId)
                    .set(OperationEntity::getSubmissionIdempotencyKey, key));
        }

        ConnectionContext ctx = new ConnectionContext(
                connection.getId(), operation.getStoreId(), connection.getPlatform(),
                decryptConfig(connection.getConfigEncrypted()));
        PlatformChange change = buildChange(operation, connection.getPlatform(), submissionKey);

        // 5. Advance pending → submitting through the state machine (Req 16.7), then submit OUTSIDE
        // any DB transaction (Req 6.3). The transient SUBMITTING state represents "call in progress";
        // confirmed acceptance advances to submitted, permanent rejection to failed.
        operationService.transition(operationId, TransitionEvent.SUBMIT);

        PlatformWriteResult result;
        try {
            result = connector.submit(ctx, change);
        } catch (Exception e) {
            // Transport/credential failures are treated as a rejection (the internal record is left
            // unchanged and the reason is recorded), never thrown to the caller.
            result = PlatformWriteResult.rejected(rootMessage(e));
        }
        if (result == null) {
            result = PlatformWriteResult.rejected("Connector returned no result");
        }

        if (result.accepted()) {
            // Advance submitting → submitted (Req 16.7), store the platform reference (Req 55.7) and
            // the raw platform result; the Operation awaits the platform's asynchronous
            // acknowledgement (callback / poll).
            operationService.transition(operationId, TransitionEvent.PLATFORM_ACCEPTED);
            operationMapper.update(null, new LambdaUpdateWrapper<OperationEntity>()
                    .eq(OperationEntity::getId, operationId)
                    .set(OperationEntity::getPlatformReference, result.platformReference())
                    .set(OperationEntity::getPlatformResult, jsonCodec.toJson(result)));
            log.info("Operation {} submitted to platform {} (ref={})",
                    operationId, connection.getPlatform(), result.platformReference());
        } else {
            // Platform rejected: record the reason, then resolve submitting → failed via
            // PERMANENT_REJECT (Req 16.7). The confirmed value is never changed on a failure.
            operationMapper.update(null, new LambdaUpdateWrapper<OperationEntity>()
                    .eq(OperationEntity::getId, operationId)
                    .set(OperationEntity::getStatusReason, truncate(result.message()))
                    .set(OperationEntity::getPlatformResult, jsonCodec.toJson(result)));
            operationService.transition(operationId, TransitionEvent.PERMANENT_REJECT);
            log.info("Operation {} rejected by platform {}: {}",
                    operationId, connection.getPlatform(), result.message());
        }

        OperationEntity reloaded = operationMapper.selectById(operationId);
        return OperationResult.from(reloaded != null ? reloaded : operation, false);
    }

    // ── change construction ─────────────────────────────────────────────────

    /**
     * Build a {@link PlatformChange} from the Operation's pending value. The Operation's
     * {@code after_value} (the pending value) is the value to set on the platform, the
     * {@code before_value} is the value before the change, and the change carries the Operation's
     * {@code submissionIdempotencyKey}. The JSON-encoded before/after values are decoded to their
     * plain text form so the connector receives scalar strings rather than quoted JSON.
     */
    private PlatformChange buildChange(OperationEntity operation, String platform, String submissionKey) {
        String changeType = (operation.getField() != null && !operation.getField().isBlank())
                ? operation.getField()
                : "update";
        return new PlatformChange(
                platform,
                operation.getStoreId(),
                changeType,
                operation.getEntityType(),
                operation.getEntityId() != null ? operation.getEntityId().toString() : null,
                jsonToText(operation.getBeforeValue()),
                jsonToText(operation.getAfterValue()),
                operation.getOperationSource(),
                operation.getId() != null ? operation.getId().toString() : null,
                submissionKey);
    }

    /**
     * Decode a stored JSON-column value to its plain text form: a JSON string {@code "PAUSED"} yields
     * {@code PAUSED} and a JSON scalar {@code 1.23} yields {@code 1.23}; an object/array yields its
     * compact JSON text. {@code null}/blank passes through as {@code null}.
     */
    private String jsonToText(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json);
            if (node.isValueNode()) {
                return node.asText();
            }
            return node.toString();
        } catch (Exception e) {
            // Not valid JSON (legacy plain value) — use the raw text.
            return json;
        }
    }

    // ── connection resolution ───────────────────────────────────────────────

    /**
     * Resolve the Store's valid, active {@link PlatformConnectionEntity} (status
     * {@value #STATUS_CONNECTED}). Because {@code applyOperation} is only reached for an Operation that
     * resolved to {@code pending} (which requires a write-capable Store at creation), a connected
     * connection is expected; its absence is a typed, retryable error.
     */
    private PlatformConnectionEntity resolveActiveConnection(UUID storeId) {
        if (storeId == null) {
            throw new BusinessException(409, "STORE_NOT_WRITE_CAPABLE",
                    "操作缺少店铺标识，无法解析平台连接");
        }
        return platformConnectionMapper.selectList(
                        new LambdaQueryWrapper<PlatformConnectionEntity>()
                                .eq(PlatformConnectionEntity::getStoreId, storeId)
                                .eq(PlatformConnectionEntity::getStatus, STATUS_CONNECTED)
                                .orderByDesc(PlatformConnectionEntity::getUpdatedAt))
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(409, "STORE_NOT_WRITE_CAPABLE",
                        "店铺没有有效的平台连接，无法提交操作：" + storeId));
    }

    private Map<String, String> decryptConfig(String stored) {
        if (stored == null || stored.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> encrypted = objectMapper.readValue(
                    stored, new TypeReference<LinkedHashMap<String, String>>() {});
            Map<String, String> plain = new LinkedHashMap<>();
            encrypted.forEach((k, v) -> plain.put(k, v == null ? null : cryptoUtil.decrypt(v)));
            return plain;
        } catch (Exception e) {
            log.warn("Failed to read platform config for Operation_Write_Back: {}", e.getMessage());
            return Map.of();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Keep the recorded status reason within the {@code status_reason} column bound (length 500). */
    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m != null ? m : cur.getClass().getSimpleName();
    }
}
