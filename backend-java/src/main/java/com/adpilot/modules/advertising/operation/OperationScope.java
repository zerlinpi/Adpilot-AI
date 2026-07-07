package com.adpilot.modules.advertising.operation;

/**
 * The execution-scope classification of an {@code Operation}.
 *
 * <ul>
 *   <li>{@link #PLATFORM_MUTATION} — a change that mutates platform-side (Amazon) state and must
 *       be submitted to the platform. These Operations create an Outbox entry, carry platform
 *       {@link SyncState} values, and route through Operation_Write_Back and the Write_Connector.</li>
 *   <li>{@link #LOCAL_CONFIGURATION} — a change to internal-only configuration that is never
 *       submitted to Amazon (for example AI personality, Goal configuration, the per-store hosting
 *       policy, and notification configuration). These Operations never create an Outbox entry,
 *       never carry a {@link SyncState}, and instead carry an {@link ExecutionStatus}.</li>
 * </ul>
 *
 * <p>Validates: Requirements 3.7, 3.8.</p>
 */
public enum OperationScope {
    PLATFORM_MUTATION,
    LOCAL_CONFIGURATION
}
