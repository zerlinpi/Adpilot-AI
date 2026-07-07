package com.adpilot.common.security;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.Set;
import java.util.UUID;

/**
 * Immutable value object describing the data a user may access after combining all
 * of their roles' data scopes (Req 7.1.4). The {@link #type} captures the broadest
 * precedence tier; the dimension collections carry the concrete identifiers that
 * tier permits.
 *
 * <p>A {@code superAdmin} scope bypasses all filtering (Req 2.2.5 / 7.1.3 super-admin
 * branch).</p>
 */
@Getter
@Builder
@ToString
public class EffectiveScope {

    /** Broadest applicable scope tier resolved across the user's roles. */
    private final ScopeType type;

    /** Stores the user may access when the tier is {@link ScopeType#ASSIGNED_STORE}. */
    @Builder.Default
    private final Set<UUID> storeIds = Set.of();

    /** Products the user may access when the tier is {@link ScopeType#ASSIGNED_PRODUCT}. */
    @Builder.Default
    private final Set<UUID> productIds = Set.of();

    /**
     * Store groups the user may access when the tier is
     * {@link ScopeType#ASSIGNED_STORE_GROUP}. Records are permitted when their store
     * belongs to one of these groups (Req 13.1 / 13.6). The union across the user's
     * roles is taken when resolving (Req 13.7).
     */
    @Builder.Default
    private final Set<UUID> storeGroupIds = Set.of();

    /** Department the user belongs to when the tier is {@link ScopeType#DEPARTMENT}. */
    private final UUID departmentId;

    /** Owning user when the tier is {@link ScopeType#OWN}. */
    private final UUID userId;

    /** When true, the user is a super administrator and all scope checks pass. */
    @Builder.Default
    private final boolean superAdmin = false;

    /** A scope that grants unrestricted access. */
    public static EffectiveScope superAdmin() {
        return EffectiveScope.builder()
                .type(ScopeType.ALL_COMPANY)
                .superAdmin(true)
                .build();
    }

    /** Whether this scope imposes no row-level restriction. */
    public boolean isUnrestricted() {
        return superAdmin || type == ScopeType.ALL_COMPANY;
    }
}
