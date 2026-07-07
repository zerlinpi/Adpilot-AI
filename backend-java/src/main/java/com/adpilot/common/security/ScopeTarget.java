package com.adpilot.common.security;

import lombok.Builder;
import lombok.Getter;

/**
 * Describes how a queried entity exposes the data-scope dimensions, so that
 * {@link DataScopeService#applyScope} can build a reusable {@code QueryWrapper}
 * fragment without knowing the concrete entity type (Req 7.1.5 shared query layer).
 *
 * <p>Each column is the physical DB column name on the table being queried; a
 * {@code null} column means the entity does not carry that dimension.</p>
 */
@Getter
@Builder
public class ScopeTarget {

    /** Column holding the owning store id (CHAR(36)). */
    private final String storeIdColumn;

    /** Column holding the owning product id (CHAR(36)). */
    private final String productIdColumn;

    /**
     * Column holding the owning store-group id (CHAR(36)). When present, list queries
     * and single-record guards filter by the account's Store_Group_Scope directly on
     * this column. When the entity carries only a store id, the store-&gt;group
     * resolution is performed via {@link #storeIdColumn} instead (Req 13.6).
     */
    private final String storeGroupIdColumn;

    /** Column holding the owning department id (CHAR(36)). */
    private final String departmentIdColumn;

    /** Column holding the record owner / creator id (CHAR(36)). */
    private final String ownerIdColumn;

    public static ScopeTarget store(String storeIdColumn) {
        return ScopeTarget.builder().storeIdColumn(storeIdColumn).build();
    }

    public static ScopeTarget storeAndOwner(String storeIdColumn, String ownerIdColumn) {
        return ScopeTarget.builder().storeIdColumn(storeIdColumn).ownerIdColumn(ownerIdColumn).build();
    }

    /** Target whose store-group dimension is exposed directly on the queried table. */
    public static ScopeTarget storeGroup(String storeGroupIdColumn) {
        return ScopeTarget.builder().storeGroupIdColumn(storeGroupIdColumn).build();
    }
}
