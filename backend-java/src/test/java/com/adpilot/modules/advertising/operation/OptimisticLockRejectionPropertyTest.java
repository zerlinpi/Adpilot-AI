package com.adpilot.modules.advertising.operation;

import com.adpilot.modules.advertising.mapper.CampaignMapper;

import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link OptimisticLockGuard}.
 *
 * <p>Feature: advertising-workspace-rework, Property 15: Optimistic-lock rejects stale writes.
 *
 * <p>Validates: Requirements 5.4, 5.5.
 *
 * <p>For any guarded update, when the mapper update affects {@code 0} rows (the object's version
 * changed since the operator's view loaded it — a stale write), the guard throws
 * {@link VersionConflictException} and rejects the Operation, carrying the {@code entityType},
 * {@code entityId}, and {@code expectedVersion} for auditing/operator messaging; when it affects
 * {@code >= 1} row (the guarded {@code WHERE id = ? AND version = ?} matched), it succeeds. There is
 * no last-writer-wins: a stale view never overwrites a newer change.
 *
 * <p>The {@link BaseMapper} is mocked (Mockito) so the property can drive the single decisive input
 * — the affected-row count returned by {@code mapper.update(...)} — across the partition
 * {@code {0}} (stale) and {@code {>= 1}} (matched), with arbitrary {@code expectedVersion} and ids.
 */
@Label("Feature: advertising-workspace-rework, Property 15: Optimistic-lock rejects stale writes")
class OptimisticLockRejectionPropertyTest {

    /** Minimum property iterations mandated by the design's Correctness Properties section. */
    private static final int MIN_ITERATIONS = 200;

    private final OptimisticLockGuard guard = new OptimisticLockGuard();

    /**
     * Feature: advertising-workspace-rework, Property 15: Optimistic-lock rejects stale writes.
     *
     * <p>Validates: Requirements 5.4, 5.5.
     *
     * <p>{@code affected == 0} (stale version) => the guard throws {@link VersionConflictException}
     * carrying the entityType/id/expectedVersion and HTTP 409 {@code VERSION_CONFLICT};
     * {@code affected >= 1} (version matched) => the guarded update completes with no exception.
     *
     * @param affectedRows the mocked affected-row count; {@code 0} models a stale write, {@code >= 1}
     *                     a matched version. Drawn across {@code [0, Integer.MAX_VALUE]} so both the
     *                     {@code {0}} and {@code {>= 1}} partitions are exercised every run.
     * @param idBits       seeds an arbitrary target id
     * @param expectedVersion an arbitrary version the operator's view was loaded with (Req 5.4)
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("affected==0 throws VersionConflictException (stale); affected>=1 succeeds (matched)")
    void zeroRowsRejectsStaleWriteOtherwiseSucceeds(
            @ForAll @IntRange(min = 0, max = Integer.MAX_VALUE) int affectedRows,
            @ForAll long idBits,
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long expectedVersion) {

        CampaignMapper mapper = mock(CampaignMapper.class);
        when(mapper.update(isNull(), any())).thenReturn(affectedRows);

        UUID id = new UUID(idBits, ~idBits);
        String entityType = "campaign";

        if (affectedRows == 0) {
            // Stale write: the version changed since the view loaded it -> reject the Operation.
            VersionConflictException conflict = catchThrowableOfType(
                    () -> guard.guardedUpdate(mapper, entityType, id, expectedVersion,
                            w -> w.set("status", "paused")),
                    VersionConflictException.class);

            assertThat(conflict)
                    .as("affected==0 must reject the stale write with a VersionConflictException")
                    .isNotNull();
            assertThat(conflict.getEntityType()).isEqualTo(entityType);
            assertThat(conflict.getEntityId()).isEqualTo(id);
            assertThat(conflict.getExpectedVersion()).isEqualTo(expectedVersion);
            assertThat(conflict.getCode()).isEqualTo(VersionConflictException.ERROR_CODE);
            assertThat(conflict.getStatus()).isEqualTo(409);
        } else {
            // Version matched (>= 1 row): the guarded update is accepted, no conflict thrown.
            assertThatCode(() -> guard.guardedUpdate(mapper, entityType, id, expectedVersion,
                    w -> w.set("status", "paused")))
                    .as("affected>=1 (matched version) must succeed without a conflict")
                    .doesNotThrowAnyException();
        }
    }
}
