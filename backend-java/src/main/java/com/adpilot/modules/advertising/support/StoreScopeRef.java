package com.adpilot.modules.advertising.support;

import java.util.UUID;

/**
 * Minimal store-bearing holder used to validate store-level ownership of an
 * advertising operation that is keyed by a Store id rather than a concrete
 * persisted record (e.g. recommendation generation, trend/diagnosis for a
 * Store, aggregated product-ad reads) (Req 24.1, 25.3).
 *
 * <p>Its single field is named {@code storeId} so the shared
 * {@code DataScopeService.assertCanRead}/{@code assertCanWrite} guards can read
 * the store dimension off it via reflection — out-of-scope or guessed store ids
 * therefore fail the scope check exactly as a real record would, without ever
 * disclosing the Store's contents (Req 25.2). This mirrors the private
 * {@code StoreScoped} holder used inside the Operation service so store-level
 * checks behave identically across the module.</p>
 */
public final class StoreScopeRef {

    @SuppressWarnings("unused")
    private final UUID storeId;

    private StoreScopeRef(UUID storeId) {
        this.storeId = storeId;
    }

    /** A scope reference for the given store id. */
    public static StoreScopeRef of(UUID storeId) {
        return new StoreScopeRef(storeId);
    }
}
