package com.adpilot.common.security;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

/**
 * Shared data-scope query layer (Req 2.2 / 7.1). Resolves a user's effective data
 * scope, contributes a reusable filter fragment to list queries, and guards
 * single-record reads and writes.
 */
public interface DataScopeService {

    /**
     * Resolve the user's effective data scope as the broadest applicable across all
     * of their roles, by the precedence
     * all-company &rarr; department &rarr; assigned-store/assigned-product &rarr; own,
     * unioning the permitted records across roles (Req 7.1.4).
     */
    EffectiveScope resolve(CurrentUser user);

    /**
     * Apply the user's effective scope to a list query by adding the appropriate
     * row-level predicate to {@code wrapper} for the given {@code target}
     * (Req 2.2.1 / 7.1.5). Super-administrators and all-company scopes add no
     * restriction.
     */
    <T> void applyScope(QueryWrapper<T> wrapper, ScopeTarget target, CurrentUser user);

    /**
     * Assert the user may read the given single record, throwing a 403
     * {@code BusinessException} when it falls outside the user's effective scope
     * (Req 2.2.2).
     */
    void assertCanRead(Object entity, CurrentUser user);

    /**
     * Assert the user may create or modify the given single record, throwing a 403
     * {@code BusinessException} when it falls outside the user's effective scope
     * (Req 2.2.3).
     */
    void assertCanWrite(Object entity, CurrentUser user);
}
