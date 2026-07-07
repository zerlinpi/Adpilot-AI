package com.adpilot.modules.advertising.operation;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * The explicit optimistic-lock guarded update for the versioned advertising entities (Req 5.4, 5.5).
 *
 * <p>No {@code OptimisticLockerInnerInterceptor} is registered for this application, so the
 * MyBatis-Plus {@code @Version} columns are otherwise inert. This guard supplies the explicit lock
 * the requirement mandates: every guarded write runs</p>
 *
 * <pre>{@code UPDATE <table> SET <fields>, version = version + 1 WHERE id = ? AND version = ?}</pre>
 *
 * <p>and treats a zero-row update result as a {@link VersionConflictException} that rejects the
 * Operation (Req 5.5). The {@code version = ?} predicate is the version the operator's view was
 * loaded with; if another writer advanced the version in the meantime the update matches no row,
 * which is exactly the stale-write rejection of Req 5.4 (no last-writer-wins).</p>
 *
 * <p>The guard is generic over any entity whose table exposes the conventional {@code id} (char(36))
 * and {@code version} columns — the convention every versioned advertising entity follows
 * (Campaign, Goal, Keyword, Ad_Group, Target, Negative_Keyword, Product_Ad). The id is bound as its
 * 36-char string form so the comparison matches the {@code char(36)} column regardless of UUID
 * type-handler configuration.</p>
 *
 * <p>Validates: Requirements 5.4, 5.5.</p>
 */
@Component
public class OptimisticLockGuard {

    /**
     * Apply an optimistic-lock guarded update that sets the supplied fields and bumps the version,
     * guarded on the expected version.
     *
     * <p>Generates {@code UPDATE <table> SET <fieldMutations>, version = version + 1
     * WHERE id = ? AND version = ?}. When the affected row count is zero, the object's version has
     * changed since {@code expectedVersion} was read, so a {@link VersionConflictException} is thrown
     * to reject the Operation (Req 5.5).</p>
     *
     * @param mapper          the MyBatis-Plus mapper for the target entity; must not be {@code null}
     * @param entityType      a label for the entity type (e.g. {@code campaign}) used in the
     *                        conflict's audit/operator message; may be {@code null}
     * @param id              the target object's id; must not be {@code null}
     * @param expectedVersion the version the operator's view was loaded with (Req 5.4)
     * @param fieldMutations  applies the field {@code set(...)} clauses to the update wrapper; may be
     *                        {@code null} to bump only the version (a pure version claim)
     * @param <T>             the entity type
     * @throws VersionConflictException when the guarded update affects zero rows (stale version)
     */
    public <T> void guardedUpdate(BaseMapper<T> mapper,
                                  String entityType,
                                  UUID id,
                                  long expectedVersion,
                                  Consumer<UpdateWrapper<T>> fieldMutations) {
        if (mapper == null) {
            throw new IllegalArgumentException("mapper must not be null");
        }
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }

        UpdateWrapper<T> wrapper = new UpdateWrapper<>();
        if (fieldMutations != null) {
            fieldMutations.accept(wrapper);
        }
        // SET ..., version = version + 1   (the field sets, if any, are applied above)
        wrapper.setSql("version = version + 1");
        // WHERE id = ? AND version = ?  — bind id as its char(36) string form.
        wrapper.eq("id", id.toString())
                .eq("version", expectedVersion);

        // entity == null: only the wrapper's SET / setSql clauses are written.
        int affected = mapper.update(null, wrapper);
        if (affected == 0) {
            // Zero rows => the version changed since the view loaded it: reject as a conflict (Req 5.5).
            throw new VersionConflictException(entityType, id, expectedVersion);
        }
    }

    /**
     * Claim an object at its expected version without changing any other field, bumping the version
     * so any concurrent stale write is rejected.
     *
     * <p>Generates {@code UPDATE <table> SET version = version + 1 WHERE id = ? AND version = ?}.
     * This is the pure optimistic-lock claim used when an Operation must guard against concurrent
     * modification of the object without yet mutating the object's confirmed value (Req 5.4, 5.5).</p>
     *
     * @param mapper          the MyBatis-Plus mapper for the target entity; must not be {@code null}
     * @param entityType      a label for the entity type used in the conflict's message; may be null
     * @param id              the target object's id; must not be {@code null}
     * @param expectedVersion the version the operator's view was loaded with (Req 5.4)
     * @param <T>             the entity type
     * @throws VersionConflictException when the guarded update affects zero rows (stale version)
     */
    public <T> void guardVersion(BaseMapper<T> mapper,
                                 String entityType,
                                 UUID id,
                                 long expectedVersion) {
        guardedUpdate(mapper, entityType, id, expectedVersion, null);
    }
}
