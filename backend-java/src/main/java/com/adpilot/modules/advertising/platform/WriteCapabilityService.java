package com.adpilot.modules.advertising.platform;

import java.util.UUID;

/**
 * Resolves whether a Store is <em>write-capable</em> — i.e. whether platform
 * execution is actually available for that Store — per the prerequisite
 * milestone of Requirement 53.
 *
 * <p>A Store is write-capable if and only if BOTH of the following hold
 * (Req 53.1, 53.2):
 * <ol>
 *   <li>the platform's {@link com.adpilot.modules.apisync.connector.PlatformWriteConnector}
 *       implementation is registered (one connector bean per platform), AND</li>
 *   <li>the Store has a valid, active
 *       {@link com.adpilot.modules.apisync.entity.PlatformConnectionEntity}.</li>
 * </ol>
 *
 * <p>When a Store is not write-capable, the Advertising_Module operates in
 * local-draft / simulation mode for that Store: {@code platform_mutation}
 * Operations resolve directly to the terminal {@code local-only} Sync_State and
 * the platform-dependent Sync_States ({@code submitted}, {@code amazon-processing},
 * {@code effective}, {@code cancel_requested}, {@code reconciliation_required})
 * are never reachable for it.
 */
public interface WriteCapabilityService {

    /**
     * Returns {@code true} iff the given Store is write-capable: a
     * {@code PlatformWriteConnector} bean exists for the Store's platform AND the
     * Store has a valid active {@code PlatformConnection} (Req 53.1, 53.2).
     *
     * @param storeId the Store to evaluate (a {@code null} id is never
     *                write-capable)
     * @return {@code true} when both the connector implementation and a valid
     *         active connection are present; {@code false} otherwise
     */
    boolean isWriteCapable(UUID storeId);
}
