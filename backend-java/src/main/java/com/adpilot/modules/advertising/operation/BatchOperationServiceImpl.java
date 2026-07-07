package com.adpilot.modules.advertising.operation;

import com.adpilot.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Default {@link BatchOperationService}.
 *
 * <p>This is intentionally a <em>thin</em> orchestration layer: it adds only the two batch-level
 * concerns of Requirement 6 and delegates every other concern (permission, ownership, idempotency,
 * conflict, optimistic-lock, approval, write-capability, and the single-transaction persistence) to
 * {@link OperationService#createOperation(CreateOperationCommand)} per item.</p>
 *
 * <p><b>Single-store gate (Req 6.5, 25.4).</b> Before attempting any item, the batch is rejected as a
 * whole if its commands reference more than one Store. Per Requirement 6.5 the cross-store records
 * are NOT silently excluded — the entire batch is refused with a typed {@link BusinessException}.</p>
 *
 * <p><b>Per-item partial success (Req 6.6, 6.7, 6.8, 36.2).</b> Each item is delegated in its OWN
 * transaction (this method is deliberately NOT {@code @Transactional}, so each {@code createOperation}
 * call commits or rolls back independently and one item's failure never aborts the others). Any
 * per-item rejection is caught and recorded as a failed {@link BatchItemResult} carrying the record
 * id and the failure reason; processing continues with the next item. The returned list mirrors the
 * input order one-to-one and reports the <em>creation</em> result — that an Operation was created and
 * persisted — never platform application (Req 6.8).</p>
 *
 * <p>Validates: Requirements 6.5, 6.6, 6.7, 6.8, 25.4, 36.2.</p>
 */
@Slf4j
@Service
public class BatchOperationServiceImpl implements BatchOperationService {

    private final OperationService operationService;

    public BatchOperationServiceImpl(OperationService operationService) {
        this.operationService = operationService;
    }

    @Override
    public List<BatchItemResult> createBatch(List<CreateOperationCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new BusinessException(400, "INVALID_BATCH", "批量操作至少需要包含一个条目");
        }

        // Cross-store batch rejection (Req 6.5, 25.4): reject the WHOLE batch when it spans more than
        // one Store rather than silently excluding the cross-store records. Null storeIds are not
        // treated as a distinct Store here — a malformed item is allowed to fail per-item below.
        long distinctStores = commands.stream()
                .map(CreateOperationCommand::getStoreId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        if (distinctStores > 1) {
            throw new BusinessException(400, "CROSS_STORE_BATCH",
                    "批量操作引用了多个店铺的记录，已整体拒绝；请按店铺分别提交");
        }

        // Single-store batch: attempt each item independently with partial-success semantics
        // (Req 6.6/6.7). Each createOperation runs in its own transaction.
        List<BatchItemResult> results = new ArrayList<>(commands.size());
        for (CreateOperationCommand command : commands) {
            results.add(attempt(command));
        }
        return results;
    }

    /**
     * Attempt one item, converting a successful {@code createOperation} into a created
     * {@link BatchItemResult} and any rejection into a failed one (Req 6.6, 6.7). The creation result
     * — not the submission or platform final result — is what is reported (Req 6.8).
     */
    private BatchItemResult attempt(CreateOperationCommand command) {
        if (command == null) {
            return BatchItemResult.builder()
                    .created(false)
                    .failureReason("批量条目为空")
                    .build();
        }
        try {
            OperationResult result = operationService.createOperation(command);
            return BatchItemResult.created(command, result);
        } catch (BusinessException e) {
            log.debug("Batch item creation failed for entity {}/{}: {}",
                    command.getEntityType(), idOf(command), e.getMessage());
            return BatchItemResult.failed(command, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Batch item creation failed unexpectedly for entity {}/{}",
                    command.getEntityType(), idOf(command), e);
            return BatchItemResult.failed(command,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private static UUID idOf(CreateOperationCommand command) {
        return command != null ? command.getEntityId() : null;
    }
}
