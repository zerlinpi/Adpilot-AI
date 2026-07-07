package com.adpilot.modules.rbac.service;

import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.rbac.dto.CreateStoreGroupCommand;
import com.adpilot.modules.rbac.vo.StoreGroupVo;

import java.util.List;
import java.util.UUID;

/**
 * First-class Store_Group management (platform-workspace-rbac Req 10, 11). A
 * Store_Group is the unit of the Store-Group Data Scope; every Store resolves to
 * exactly one Store_Group of its platform family ({@code amazon} or
 * {@code independent_site}).
 *
 * <p>All operations are scoped to the caller's organization. Validation failures
 * (blank/over-length/duplicate name, platform-family mismatch, unknown family)
 * surface as a {@link com.adpilot.common.exception.BusinessException}.</p>
 */
public interface StoreGroupService {

    /**
     * Create a Store_Group with a validated name and platform family, returning
     * the persisted view with its generated identifier (Req 10.3).
     *
     * @throws com.adpilot.common.exception.BusinessException when the name is
     *     blank, exceeds 100 characters, duplicates an existing group within the
     *     same platform family, or the family is not a Store_Group family (Req 10.1, 10.4)
     */
    StoreGroupVo create(CreateStoreGroupCommand cmd);

    /**
     * Rename an existing Store_Group, keeping the name unique within the group's
     * platform family (Req 10.4).
     *
     * @throws com.adpilot.common.exception.BusinessException when the group is not
     *     found, the name is blank/over-length, or the new name collides
     */
    StoreGroupVo rename(UUID id, String name);

    /**
     * (Re)assign a Store to a Store_Group of the same platform family and apply
     * the new association to subsequent data-scope evaluations (Req 10.5).
     *
     * @throws com.adpilot.common.exception.BusinessException when the store or
     *     group is not found, or their platform families do not match (Req 10.6, 11.4)
     */
    void assignStore(UUID storeId, UUID storeGroupId);

    /**
     * List the Store_Groups in the caller's organization for the given platform
     * family, ordered by name. Newly created Amazon groups appear immediately
     * (Req 11.1, 11.2, 11.3).
     */
    List<StoreGroupVo> listByFamily(PlatformFamily family);

    /**
     * Resolve the per-family default Store_Group for the caller's organization —
     * the fallback for Stores with no explicit assignment (Req 10.7).
     *
     * @throws com.adpilot.common.exception.BusinessException when no default group
     *     exists for the family in the caller's organization
     */
    UUID defaultGroupFor(PlatformFamily family);
}
