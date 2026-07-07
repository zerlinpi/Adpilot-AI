package com.adpilot.modules.advertising.operation;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Default {@link IdempotencyService}, backed by the {@code operations} table through
 * {@link OperationMapper}.
 *
 * <p>The two idempotency layers are kept strictly distinct (Req 5.2, 5.3, 5.7):</p>
 * <ul>
 *   <li>Click coalescing reads existing attempts by {@code logical_idempotency_key} (scoped to the
 *       Store) so repeated activations resolve to the SAME logical Operation.</li>
 *   <li>Each submission attempt is given its OWN fresh {@code submissionIdempotencyKey}, so a retry
 *       is never blocked by a prior attempt's key; only an exact re-delivery of the SAME submission
 *       — recognized by an already-settled attempt carrying that key — is treated as a duplicate.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyServiceImpl implements IdempotencyService {

    /**
     * The settled platform Sync_States that mark a submission's result as final. A re-delivery
     * carrying a key whose attempt is in one of these states is a duplicate and MUST NOT produce a
     * new platform request (Req 5.2, 5.7).
     */
    private static final Set<SyncState> SETTLED_PLATFORM_RESULTS =
            EnumSet.of(SyncState.EFFECTIVE, SyncState.FAILED, SyncState.CANCELLED);

    private final OperationMapper operationMapper;

    @Override
    public Optional<OperationEntity> findLogicalOperation(UUID storeId, String logicalIdempotencyKey) {
        if (storeId == null || logicalIdempotencyKey == null || logicalIdempotencyKey.isBlank()) {
            return Optional.empty();
        }
        // Coalesce by logical_idempotency_key within the Store; return the most recent attempt so its
        // logicalOperationId identifies the logical change to attach further attempts to (Req 5.3).
        List<OperationEntity> matches = operationMapper.selectList(
                new LambdaQueryWrapper<OperationEntity>()
                        .eq(OperationEntity::getStoreId, storeId)
                        .eq(OperationEntity::getLogicalIdempotencyKey, logicalIdempotencyKey)
                        .orderByDesc(OperationEntity::getAttemptNumber)
                        .orderByDesc(OperationEntity::getCreatedAt));
        return matches.stream().findFirst();
    }

    @Override
    public String newSubmissionIdempotencyKey() {
        // A fresh per-attempt key. Random UUIDs are unique per attempt, so a retry is never deduped
        // against a prior attempt's submission key (Req 5.2, 5.7).
        return UUID.randomUUID().toString();
    }

    @Override
    public Optional<OperationEntity> findBySubmissionIdempotencyKey(String submissionIdempotencyKey) {
        if (submissionIdempotencyKey == null || submissionIdempotencyKey.isBlank()) {
            return Optional.empty();
        }
        // Each attempt owns a unique submission key, so at most one Operation matches; order defensively
        // so a single, deterministic attempt is returned even if data were ever duplicated.
        List<OperationEntity> matches = operationMapper.selectList(
                new LambdaQueryWrapper<OperationEntity>()
                        .eq(OperationEntity::getSubmissionIdempotencyKey, submissionIdempotencyKey)
                        .orderByDesc(OperationEntity::getCreatedAt));
        return matches.stream().findFirst();
    }

    @Override
    public boolean isSubmissionProcessed(String submissionIdempotencyKey) {
        return findBySubmissionIdempotencyKey(submissionIdempotencyKey)
                .map(OperationEntity::getSyncState)
                .filter(state -> !state.isBlank())
                .map(IdempotencyServiceImpl::isSettledPlatformResult)
                .orElse(false);
    }

    /**
     * @param syncState the stored {@code sync_state} machine value
     * @return {@code true} iff the value names a settled platform result ({@code effective},
     *         {@code failed}, or {@code cancelled}). Compared case-insensitively against the
     *         {@link SyncState} names; these three values contain no separators, so the comparison is
     *         independent of the hyphen/underscore convention used for multi-word states.
     */
    private static boolean isSettledPlatformResult(String syncState) {
        return SETTLED_PLATFORM_RESULTS.stream()
                .anyMatch(settled -> settled.name().equalsIgnoreCase(syncState.trim()));
    }
}
