package com.adpilot.modules.advertising.operation;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * The single, authoritative conversion between the {@code Operation} classification/lifecycle enums
 * ({@link OperationSource}, {@link OperationScope}, {@link SyncState}, {@link ExecutionStatus}) and
 * the canonical lowercase machine values persisted in the {@code operations} table and exchanged on
 * the API contract.
 *
 * <p>The {@link OperationEntity} javadoc states that the string-valued status columns hold the
 * canonical machine values and that "enum conversion is owned by the service / state-machine layer".
 * This class is that owner. It exists because the machine values are NOT all derivable from
 * {@link Enum#name()}: most use the underscore form ({@code awaiting_approval},
 * {@code cancel_requested}, {@code reconciliation_required}, {@code one_click_optimize}), but the
 * Sync_States {@code local-only} and {@code amazon-processing} use a HYPHEN per the Requirement
 * glossary. Centralizing the mapping here keeps the enums pure (no persistence concern) and
 * guarantees the persistence layer and the API contract agree on exactly one spelling.</p>
 *
 * <p>Validates: Requirements 3.7, 3.8, 4.1, 8.1.</p>
 */
public final class OperationMachineValues {

    private OperationMachineValues() {
        // Utility class — not instantiable.
    }

    private static final Map<OperationSource, String> SOURCE_TO_VALUE = new EnumMap<>(OperationSource.class);
    private static final Map<String, OperationSource> VALUE_TO_SOURCE = new HashMap<>();

    private static final Map<OperationScope, String> SCOPE_TO_VALUE = new EnumMap<>(OperationScope.class);
    private static final Map<String, OperationScope> VALUE_TO_SCOPE = new HashMap<>();

    private static final Map<SyncState, String> SYNC_TO_VALUE = new EnumMap<>(SyncState.class);
    private static final Map<String, SyncState> VALUE_TO_SYNC = new HashMap<>();

    private static final Map<ExecutionStatus, String> EXEC_TO_VALUE = new EnumMap<>(ExecutionStatus.class);
    private static final Map<String, ExecutionStatus> VALUE_TO_EXEC = new HashMap<>();

    static {
        register(SOURCE_TO_VALUE, VALUE_TO_SOURCE, OperationSource.MANUAL, "manual");
        register(SOURCE_TO_VALUE, VALUE_TO_SOURCE, OperationSource.RECOMMENDATION, "recommendation");
        register(SOURCE_TO_VALUE, VALUE_TO_SOURCE, OperationSource.ONE_CLICK_OPTIMIZE, "one_click_optimize");
        register(SOURCE_TO_VALUE, VALUE_TO_SOURCE, OperationSource.AI_HOSTING, "ai_hosting");
        register(SOURCE_TO_VALUE, VALUE_TO_SOURCE, OperationSource.CREATION, "creation");

        register(SCOPE_TO_VALUE, VALUE_TO_SCOPE, OperationScope.PLATFORM_MUTATION, "platform_mutation");
        register(SCOPE_TO_VALUE, VALUE_TO_SCOPE, OperationScope.LOCAL_CONFIGURATION, "local_configuration");

        // NOTE the hyphen in local-only and amazon-processing (Requirement glossary).
        register(SYNC_TO_VALUE, VALUE_TO_SYNC, SyncState.LOCAL_ONLY, "local-only");
        register(SYNC_TO_VALUE, VALUE_TO_SYNC, SyncState.PENDING, "pending");
        register(SYNC_TO_VALUE, VALUE_TO_SYNC, SyncState.AWAITING_APPROVAL, "awaiting_approval");
        register(SYNC_TO_VALUE, VALUE_TO_SYNC, SyncState.SUBMITTING, "submitting");
        register(SYNC_TO_VALUE, VALUE_TO_SYNC, SyncState.SUBMITTED, "submitted");
        register(SYNC_TO_VALUE, VALUE_TO_SYNC, SyncState.AMAZON_PROCESSING, "amazon-processing");
        register(SYNC_TO_VALUE, VALUE_TO_SYNC, SyncState.EFFECTIVE, "effective");
        register(SYNC_TO_VALUE, VALUE_TO_SYNC, SyncState.FAILED, "failed");
        register(SYNC_TO_VALUE, VALUE_TO_SYNC, SyncState.CANCEL_REQUESTED, "cancel_requested");
        register(SYNC_TO_VALUE, VALUE_TO_SYNC, SyncState.CANCELLED, "cancelled");
        register(SYNC_TO_VALUE, VALUE_TO_SYNC, SyncState.SUPERSEDED, "superseded");
        register(SYNC_TO_VALUE, VALUE_TO_SYNC, SyncState.EXPIRED, "expired");
        register(SYNC_TO_VALUE, VALUE_TO_SYNC, SyncState.RECONCILIATION_REQUIRED, "reconciliation_required");

        register(EXEC_TO_VALUE, VALUE_TO_EXEC, ExecutionStatus.APPLIED, "applied");
        register(EXEC_TO_VALUE, VALUE_TO_EXEC, ExecutionStatus.FAILED, "failed");
        register(EXEC_TO_VALUE, VALUE_TO_EXEC, ExecutionStatus.CANCELLED, "cancelled");
    }

    private static <E extends Enum<E>> void register(Map<E, String> toValue, Map<String, E> toEnum, E key, String value) {
        toValue.put(key, value);
        toEnum.put(value, key);
    }

    // ---- Operation_Source ----------------------------------------------------------------------

    public static String toValue(OperationSource source) {
        return source == null ? null : SOURCE_TO_VALUE.get(source);
    }

    public static OperationSource toOperationSource(String value) {
        if (value == null) {
            return null;
        }
        OperationSource source = VALUE_TO_SOURCE.get(value);
        if (source == null) {
            throw new IllegalArgumentException("Unknown Operation_Source machine value: " + value);
        }
        return source;
    }

    // ---- operationScope -------------------------------------------------------------------------

    public static String toValue(OperationScope scope) {
        return scope == null ? null : SCOPE_TO_VALUE.get(scope);
    }

    public static OperationScope toOperationScope(String value) {
        if (value == null) {
            return null;
        }
        OperationScope scope = VALUE_TO_SCOPE.get(value);
        if (scope == null) {
            throw new IllegalArgumentException("Unknown operationScope machine value: " + value);
        }
        return scope;
    }

    // ---- Sync_State -----------------------------------------------------------------------------

    public static String toValue(SyncState state) {
        return state == null ? null : SYNC_TO_VALUE.get(state);
    }

    public static SyncState toSyncState(String value) {
        if (value == null) {
            return null;
        }
        SyncState state = VALUE_TO_SYNC.get(value);
        if (state == null) {
            throw new IllegalArgumentException("Unknown Sync_State machine value: " + value);
        }
        return state;
    }

    // ---- executionStatus ------------------------------------------------------------------------

    public static String toValue(ExecutionStatus status) {
        return status == null ? null : EXEC_TO_VALUE.get(status);
    }

    public static ExecutionStatus toExecutionStatus(String value) {
        if (value == null) {
            return null;
        }
        ExecutionStatus status = VALUE_TO_EXEC.get(value);
        if (status == null) {
            throw new IllegalArgumentException("Unknown executionStatus machine value: " + value);
        }
        return status;
    }
}
