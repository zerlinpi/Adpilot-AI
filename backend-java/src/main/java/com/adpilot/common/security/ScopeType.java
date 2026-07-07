package com.adpilot.common.security;

/**
 * The data-scope dimensions supported by the platform, ordered from broadest to
 * narrowest. The ordinal precedence is used when resolving the effective scope
 * across a user's roles (Req 7.1.4):
 *
 * <pre>
 *   ALL_COMPANY (broadest) -&gt; DEPARTMENT -&gt; ASSIGNED_STORE / ASSIGNED_PRODUCT / ASSIGNED_STORE_GROUP -&gt; OWN (narrowest)
 * </pre>
 *
 * {@code ASSIGNED_STORE}, {@code ASSIGNED_PRODUCT}, and {@code ASSIGNED_STORE_GROUP}
 * share the same precedence tier; see {@link #rank()}.
 */
public enum ScopeType {
    ALL_COMPANY,
    DEPARTMENT,
    ASSIGNED_STORE,
    ASSIGNED_PRODUCT,
    ASSIGNED_STORE_GROUP,
    OWN;

    /**
     * Precedence rank where a lower value is broader. {@code ASSIGNED_STORE},
     * {@code ASSIGNED_PRODUCT}, and {@code ASSIGNED_STORE_GROUP} intentionally share
     * rank 2 because they are peers in the precedence ordering.
     */
    public int rank() {
        return switch (this) {
            case ALL_COMPANY -> 0;
            case DEPARTMENT -> 1;
            case ASSIGNED_STORE, ASSIGNED_PRODUCT, ASSIGNED_STORE_GROUP -> 2;
            case OWN -> 3;
        };
    }

    /**
     * Parse a persisted {@code data_scopes.scope_type} value (e.g. {@code all_company},
     * {@code assigned_store}) into a {@link ScopeType}. Unknown values resolve to the
     * narrowest scope ({@link #OWN}) so that an unrecognised configuration never widens
     * access.
     */
    public static ScopeType fromCode(String code) {
        if (code == null) {
            return OWN;
        }
        return switch (code.trim().toLowerCase()) {
            case "all_company", "all", "company" -> ALL_COMPANY;
            case "department", "dept" -> DEPARTMENT;
            case "assigned_store", "store" -> ASSIGNED_STORE;
            case "assigned_product", "product" -> ASSIGNED_PRODUCT;
            case "assigned_store_group", "store_group", "storegroup", "group" -> ASSIGNED_STORE_GROUP;
            case "own", "self" -> OWN;
            default -> OWN;
        };
    }
}
