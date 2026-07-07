# Implementation Plan: Advertising Workspace Rework

## Overview

This plan converts the design into incremental, code-only steps for the AdPilot platform (Java 17 / Spring Boot backend in `backend-java/`, MyBatis-Plus + JPA + MySQL + Redis; React/TypeScript frontend in `frontend/`). It builds the Operation model (scope, Sync_State, Outbox), the extended `PlatformWriteConnector` contract, the generalized `Operation_Write_Back`, the Pending_Overlay, executable AI hosting/personality, recommendation/engine correctness, the canonical API schema, permission/data-scope enforcement, and the four-group advertising frontend.

Each step builds on the previous and ends by wiring the new capability into a controller, worker, or page so there is no orphaned code. Property-based tests are derived directly from the design's "Correctness Properties" section: backend properties use **jqwik**, frontend properties use **fast-check + Vitest**, each property is one test running **minimum 100 iterations**, tagged `Feature: advertising-workspace-rework, Property {N}`. Test sub-tasks are marked optional with `*`.

## Tasks

- [x] 1. Confirm prerequisite API contracts
  - [x] 1.1 Verify/implement the three precondition endpoints
    - Ensure campaign update accepts `PATCH` with JSON `{dailyBudget}` and applies it; keyword update accepts `PATCH` and applies supplied fields; generate-recommendations takes `storeId` as a query parameter and returns the generated count
    - Adjust controllers under `modules/advertising/controller` only if the current shape diverges
    - _Requirements: 1.1, 1.3, 1.5_

  - [x] 1.2 Write contract tests for the precondition endpoints
    - Assert frontend request shape (`PATCH {dailyBudget}`, `PATCH` keyword, `storeId` query) matches backend accepted contract and response includes generated count
    - _Requirements: 1.2, 1.4, 1.6_

- [x] 2. Establish schema, entities, and seeds
  - [x] 2.1 Add new tables to `db/schema.sql`
    - Add `operations`, `operation_pending_changes`, `operation_outbox`, `personality_policies`, `object_status_migration_exceptions`, `acos_migration_exceptions`, `optimization_goal_migration_exceptions`, and `campaign_product_links` with the columns and indexes defined in the design Data Models section; define migration + rollback
    - _Requirements: 6.1, 7.1, 8.1, 49.5, 16.6, 17.6, 57.3, 15.4, 51.8_

  - [x] 2.2 Add columns and optimistic-lock versions to existing entities
    - Add `campaign_personality`, `origin`, `amazon_campaign_id` to `campaigns`; `default_personality` + hosting policy fields to `stores`; `timezone` to `marketplaces`; `@Version version` to `campaigns`, `goals`, `keywords`, `ad_groups`, `targets`, `negative_keywords`, `product_ads`; persist `parent_asin`/`targeting_goal` (or via `campaign_product_links`)
    - Update the corresponding JPA/MyBatis-Plus entity classes
    - _Requirements: 5.4, 12.6, 12.7, 49.2, 51.12, 15.4_

  - [x] 2.3 Seed personality policies and permission codes
    - Seed `personality_policies` with the Requirement 49.5 defaults per `(scope, personality)` incl. `rule_version`; add the `advertising:execute` permission code to the permission seed
    - _Requirements: 49.5, 49.6, 27.1_

  - [x] 2.4 Create entities, mappers, and repositories for the new tables
    - Add entity + MyBatis-Plus mapper (and JPA repository where used) for `operations`, `operation_pending_changes`, `operation_outbox`, `personality_policies`, the migration-exception tables, and `campaign_product_links`
    - _Requirements: 8.1, 6.1, 7.1, 49.5_

  - [x] 2.5 Write schema smoke/structural checks
    - Assert no duplicate pending column per field, seeded permission codes present, personality policy seed present
    - _Requirements: 7.1, 27.1, 49.5_

- [x] 3. Build Operation enums and state machine
  - [x] 3.1 Define operation enums
    - Add `OperationScope`, `SyncState` (incl. the `UNSETTLED` set), `ExecutionStatus`, `OperationSource` under `modules/advertising/operation`
    - _Requirements: 3.7, 3.8, 4.1, 7.1_

  - [x] 3.2 Implement the pure `OperationStateMachine`
    - Implement `canTransition(from, to)` and `transition(from, event)` encoding exactly the Requirement 4.1 legal transitions; make it the single authority
    - _Requirements: 4.1, 3.1_

  - [x] 3.3 Write property test for the state machine
    - **Property 4: Only legal Sync_State transitions are permitted**
    - **Validates: Requirements 4.1, 3.1**

- [x] 4. Extend the platform connector contract and write-capability resolution
  - [x] 4.1 Extend the `PlatformWriteConnector` SPI
    - Add `queryStatus`, `requestCancel`, `supportsCancel` (default false), and `mapPlatformStatus(platformStatus) -> SyncState` as default-method extensions so `core-platform-completion` stays reconciled; store `platformReference` from `submit`
    - _Requirements: 55.1, 55.2, 55.3, 55.4, 55.7_

  - [x] 4.2 Implement `WriteCapabilityService.isWriteCapable(storeId)`
    - Return true iff a connector bean for the store's platform exists AND the store has a valid active `PlatformConnection`
    - _Requirements: 53.1, 53.2_

  - [x] 4.3 Write property test for the write-capability predicate
    - **Property 3: Write-capability predicate**
    - **Validates: Requirements 53.1, 53.2**

  - [x] 4.4 Write property test for queryStatus idempotency and status mapping totality
    - **Property 76: queryStatus is idempotent and non-mutating; status mapping is total**
    - **Validates: Requirements 55.2, 55.4, 55.6**

- [x] 5. Implement Operation persistence and the idempotency/locking core
  - [x] 5.1 Implement the `Operation_Record` persistence and audit-field completeness
    - Persist all required audit fields (source, scope, key model, before/after, reversible, affected count, acting user, timestamps, resulting state, statusReason, executionStatus vs sync_state separation, AI decision fields)
    - _Requirements: 8.1, 3.8, 22.10, 49.9, 49.15_

  - [x] 5.2 Implement `IdempotencyService`
    - Coalesce repeated activations by `logicalIdempotencyKey`; issue a new `submissionIdempotencyKey` per attempt so a retry is not blocked by a prior submission key
    - _Requirements: 5.2, 5.3, 5.7_

  - [x] 5.3 Implement the optimistic-lock guarded update
    - Use `WHERE id = ? AND version = ?`; convert a zero-row result into a typed version-conflict that rejects the Operation
    - _Requirements: 5.4, 5.5_

  - [x] 5.4 Implement the in-flight conflict lock
    - Reject a new conflicting Operation against an object that already has an Operation in any Unsettled_State, using the `(entity_type, entity_id, sync_state)` index
    - _Requirements: 5.6_

  - [x] 5.5 Write property test for submission idempotency vs retry independence
    - **Property 13: Submission idempotency vs retry independence**
    - **Validates: Requirements 5.2, 5.7**

  - [x] 5.6 Write property test for repeated-activation coalescing
    - **Property 14: Repeated activations coalesce into one logical Operation**
    - **Validates: Requirements 5.3**

  - [x] 5.7 Write property test for optimistic-lock rejection
    - **Property 15: Optimistic-lock rejects stale writes**
    - **Validates: Requirements 5.4, 5.5**

  - [x] 5.8 Write property test for the in-flight conflict lock
    - **Property 16: In-flight conflict lock**
    - **Validates: Requirements 5.6**

  - [x] 5.9 Write property test for Operation_Record completeness
    - **Property 22: Operation_Record completeness and statusReason coverage**
    - **Validates: Requirements 8.1, 3.8, 22.10, 49.9, 49.15**

  - [x] 5.10 Write property test for origin/source immutability
    - **Property 28: origin and Operation_Source are immutable**
    - **Validates: Requirements 12.6**

- [x] 6. Implement `OperationService.createOperation`
  - [x] 6.1 Implement the createOperation pipeline and single-transaction persistence
    - Run, in order: permission check, data-scope + ownership validation, in-flight conflict lock, idempotency coalescing, optimistic-version check, approval-threshold evaluation, write-capability resolution (skip Outbox and resolve to terminal `local-only` when not write-capable); then persist Operation + pending-change + audit + Outbox in one transaction with no platform call; `local_configuration` never writes Outbox
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.6, 3.2, 3.7, 6.1, 6.2, 6.3, 9.4, 53.3_

  - [x] 6.2 Implement bulk/batch operation handling
    - Reject the whole batch if it spans more than one Store; otherwise attempt each item independently and return a uniform per-item creation result (record id, creation result, failure reason) representing creation, not platform application
    - _Requirements: 6.5, 6.6, 6.7, 6.8, 25.4, 36.2_

  - [x] 6.3 Enforce the Saved_View no-Operation rule
    - Ensure Saved_View create/update/delete creates no Operation_Record and no operation-log entry
    - _Requirements: 3.9_

  - [x] 6.4 Write property test for operation-scope routing
    - **Property 1: Operation scope determines routing**
    - **Validates: Requirements 2.1, 2.2, 2.3, 2.6, 3.7, 6.2, 9.1, 9.2, 9.3, 21.3**

  - [x] 6.5 Write property test for write-capability gating to local-only
    - **Property 2: Write-capability gating yields local-only**
    - **Validates: Requirements 2.4, 3.2, 9.4, 12.5, 21.5, 53.3**

  - [x] 6.6 Write integration test for the atomic, externally side-effect-free first transaction
    - **Property 19: First transaction is atomic and side-effect-free externally**
    - **Validates: Requirements 6.1, 6.3**

  - [x] 6.7 Write property test for batch scope and per-item result semantics
    - **Property 20: Batch scope and per-item result semantics**
    - **Validates: Requirements 6.5, 6.6, 6.7, 6.8, 36.2, 25.4**

  - [x] 6.8 Write property test for the Saved_View no-Operation rule
    - **Property 24: Saved_View change creates no Operation**
    - **Validates: Requirements 3.9**

- [x] 7. Implement transitions, cancel, retry, undo, supersede, approve/reject, and publish
  - [x] 7.1 Implement `transition()` and the confirmed-value-on-effective invariant
    - Route every sync_state change through the state machine; set the entity's confirmed value to the after value exactly when the Operation becomes `effective`, never otherwise
    - _Requirements: 4.x, 3.4, 3.5, 4.11, 6.4, 7.4, 7.5, 12.1_

  - [x] 7.2 Implement `cancel()` routing by submission state
    - Transition directly to `cancelled` when `pending`/`awaiting_approval`; transition to `cancel_requested` (never directly `cancelled`) when `submitted`/`amazon-processing`
    - _Requirements: 4.7, 4.8, 56.4_

  - [x] 7.3 Implement `retry()` as a fresh attempt
    - Create a new `pending` attempt with new `attemptId`, new `submissionIdempotencyKey`, incremented `attemptNumber` under the same `logicalOperationId`/`logicalIdempotencyKey`; leave the original failed record unchanged
    - _Requirements: 4.2, 7.8_

  - [x] 7.4 Implement `undo()` as a compensating Operation
    - Offer undo iff `effective` AND `reversible` AND before value still valid; create a new compensating Operation_Record rather than deleting the original
    - _Requirements: 8.4, 8.5, 56.3, 22.10_

  - [x] 7.5 Implement supersession routing
    - Route a replaced in-flight Operation directly to `superseded` when not yet submitted, else through the `cancel_requested` path; confirmed value unchanged
    - _Requirements: 4.11, 49.14_

  - [x] 7.6 Implement `approve()` / `reject()`
    - `awaiting_approval` → `submitted` on approve; `awaiting_approval` → `cancelled` on reject
    - _Requirements: 4.1, 22.8_

  - [x] 7.7 Implement `publishLocalDraft()`
    - Create a NEW `pending` `platform_mutation` Operation with its own `logicalOperationId` and a `parentOperationId` referencing the draft; do not transition the original `local-only` Operation
    - _Requirements: 12.10_

  - [x] 7.8 Implement effective-with-external-version-change reconciliation
    - When an Operation becomes `effective` but the local version changed during execution, record both values and require explicit operator resolution rather than last-writer-wins
    - _Requirements: 5.8_

  - [x] 7.9 Write property test for the confirmed-value invariant
    - **Property 5: Confirmed value changes only on effective**
    - **Validates: Requirements 3.4, 3.5, 4.4, 4.7, 4.11, 6.4, 7.4, 7.5, 12.1**

  - [x] 7.10 Write property test for cancellation routing
    - **Property 6: Cancellation routes by submission state**
    - **Validates: Requirements 4.7, 4.8, 56.4**

  - [x] 7.11 Write property test for supersession routing
    - **Property 9: Supersession routes by submission state**
    - **Validates: Requirements 4.11, 49.14**

  - [x] 7.12 Write property test for retry creating a fresh attempt
    - **Property 10: Retry creates a fresh attempt without mutating the original**
    - **Validates: Requirements 4.2, 7.8**

  - [x] 7.13 Write property test for external-version-change reconciliation
    - **Property 17: Effective-with-external-version-change requires explicit reconciliation**
    - **Validates: Requirements 5.8**

  - [x] 7.14 Write property test for undo availability
    - **Property 23: Undo availability predicate**
    - **Validates: Requirements 8.4, 8.5, 56.3, 22.10**

  - [x] 7.15 Write property test for publishing a local-only draft
    - **Property 30: Publishing a local-only draft creates a new linked Operation**
    - **Validates: Requirements 12.10**

- [x] 8. Implement the Pending_Overlay
  - [x] 8.1 Implement `PendingOverlayService`
    - Left-join each entity row to its latest Unsettled_State Operation for `(entityType, entityId, field)`, producing `confirmedValue` + optional `pendingValue` + `pendingSyncState`; no second physical column per field
    - _Requirements: 7.1, 7.3, 7.6, 7.7_

  - [x] 8.2 Write property test for the Pending_Overlay
    - **Property 21: Pending_Overlay surfaces confirmed and pending for every Unsettled_State**
    - **Validates: Requirements 7.3, 7.6, 7.7**

- [x] 9. Checkpoint - Operation core
  - Ensure all tests pass, ask the user if questions arise.

- [x] 10. Implement async workers and the callback endpoint
  - [x] 10.1 Implement the `OutboxWorker`
    - Claim `operation_outbox` rows (`FOR UPDATE SKIP LOCKED` / version-claim), submit through the connector outside any DB transaction, advance Sync_State idempotently, isolate per-row failures, bound automatic retries to ≤3 then require explicit operator retry
    - _Requirements: 4.5, 6.3, 51.6_

  - [x] 10.2 Implement the `StatusPoller` / `TimeoutSweeper`
    - For `submitted`/`amazon-processing`, call `queryStatus`; transition to `expired` after the configurable 15-minute timeout; resolve `cancel_requested`, `expired`, and `reconciliation_required` by querying actual platform state to `effective`/`failed`/`cancelled` (never `failed` from a failed cancel; never `expired`→`failed` without a query)
    - _Requirements: 4.4, 4.9, 4.10, 55.3, 56.5_

  - [x] 10.3 Implement the signature-verified `CallbackController`
    - Verify callback signature before any state mutation; reject unverified callbacks; process transitions idempotently by `submissionIdempotencyKey`; do not process callbacks for not-write-capable stores
    - _Requirements: 55.5, 55.6, 5.7, 53.2_

  - [x] 10.4 Implement `WriteBackAlerting` and notification dedupe/lifecycle
    - Raise an alert when, over the rolling window with the configured minimum sample, failure rate ≥ threshold OR ≥ configured consecutive failures for one store+connector; produce a notification only when no open notification shares its dedupe key and close stale notifications when resolved; thresholds configurable
    - _Requirements: 23.2, 23.3, 51.7, 51.11_

  - [x] 10.5 Mask secrets in platform-request logging
    - Ensure no credential/secret value appears in emitted logs
    - _Requirements: 51.5_

  - [x] 10.6 Write property test for cancel_requested resolution
    - **Property 7: cancel_requested resolves without ever marking the original failed**
    - **Validates: Requirements 4.9, 55.3**

  - [x] 10.7 Write property test for expired/reconciliation resolution via platform query
    - **Property 8: expired and reconciliation_required resolve via platform query**
    - **Validates: Requirements 4.10, 56.5**

  - [x] 10.8 Write property test for bounded automatic retries
    - **Property 11: Automatic retries are bounded**
    - **Validates: Requirements 4.5**

  - [x] 10.9 Write property test for not-write-capable stores never entering platform states
    - **Property 12: Not-write-capable stores never enter platform states**
    - **Validates: Requirements 4.12, 53.2**

  - [x] 10.10 Write property test for idempotent callback/poll processing
    - **Property 18: Callback/poll processing is idempotent**
    - **Validates: Requirements 5.7, 55.6**

  - [x] 10.11 Write property test for notification dedupe and lifecycle
    - **Property 75: Notification dedupe and lifecycle**
    - **Validates: Requirements 23.2, 23.3**

  - [x] 10.12 Write property test for signature-verified callbacks
    - **Property 77: Inbound callbacks are signature-verified before acting**
    - **Validates: Requirements 55.5**

  - [x] 10.13 Write property test for platform-reference correlation
    - **Property 78: Platform reference correlates connector interactions**
    - **Validates: Requirements 55.7**

  - [x] 10.14 Write property test for write-back failure alerting threshold
    - **Property 80: Write-back failure alerting threshold**
    - **Validates: Requirements 51.7, 51.11**

  - [x] 10.15 Write property test for secret masking in logs
    - **Property 81: Secrets are masked in logs**
    - **Validates: Requirements 51.5**

- [x] 11. Generalize Operation_Write_Back and reconcile recommendation apply
  - [x] 11.1 Implement `OperationWriteBackImpl.applyOperation(operationId)`
    - Route ONLY `platform_mutation`; resolve the Active_Store's `PlatformConnection` and the platform's `PlatformWriteConnector`, build a `PlatformChange` from the pending value, submit with the Operation's `submissionIdempotencyKey`
    - _Requirements: 2.3, 9.1, 53.5_

  - [x] 11.2 Reconcile `WriteBackService.apply(recommendationId)` to delegate
    - Have the existing recommendation-only path create a `recommendation`-sourced Operation and call `applyOperation`, preserving its approval gating; mark a recommendation/one-click/hosting action effective only when its Operation reaches `effective`
    - _Requirements: 2.3, 9.1, 9.5_

  - [x] 11.3 Write property test for recommendations never effective without an effective Operation
    - **Property 25: Recommendations are never marked effective without an effective Operation**
    - **Validates: Requirements 9.5**

- [x] 12. Checkpoint - write-back and async
  - Ensure all tests pass, ask the user if questions arise.

- [x] 13. Implement executable AI hosting and personality
  - [x] 13.1 Implement `PersonalityResolver`
    - Resolve effective AI_Personality: Campaign override > Goal default > Store default > `balanced`
    - _Requirements: 49.2, 49.3_

  - [x] 13.2 Implement `PersonalityPolicyService`
    - Load the configurable `personality_policies` table (numeric control fields incl. approval ratios and max ratios) and expose the in-effect `rule_version`
    - _Requirements: 49.5, 49.6, 49.9_

  - [x] 13.3 Implement `SafetyBoundaryResolver`
    - Resolve each limit by precedence Campaign override > Goal boundary > Store policy > System default
    - _Requirements: 22.11, 49.11_

  - [x] 13.4 Extend the pure `HostingBidOptimizer.adjustBid` clamp
    - Apply at most min(personality-allowed magnitude, resolved Safety_Boundary); keep value within [minBid, maxBid]; never widen a limit; move an already-out-of-range value only toward the safe range and flag the Campaign
    - _Requirements: 22.3, 22.4, 22.5, 22.6, 49.4, 49.10, 49.11_

  - [x] 13.5 Refactor `AiHostingOptimizer` onto the Operation path with phase gating
    - Replace direct `bid_changes`/`keyword.bid` writes with `createOperation(... source=AI_HOSTING)`; record trigger metric, resolved personality, `Personality_Rule_Version`, allowed/actual magnitude, reason, before/after, predicted impact, approval flag; gate emitted/executable adjustment types by `adpilot.hosting.phase` (V1=bid, V2=+budget, V3=+keyword/negative); require explicit confirmation for personality switches and never auto-recompute-and-execute all on a personality change; on hosting off / global pause, generate no new ops, cancel `awaiting_approval` hosting ops, leave in-flight ops to resolve
    - _Requirements: 9.3, 22.1, 22.2, 22.9, 22.12, 49.8, 49.9, 49.12, 54.1, 54.2, 54.3, 54.4_

  - [x] 13.6 Write property test for AI_Personality resolution precedence
    - **Property 48: AI_Personality resolution precedence**
    - **Validates: Requirements 49.2, 49.3**

  - [x] 13.7 Write property test for Safety_Boundary bounding the applied magnitude
    - **Property 49: Safety_Boundary always bounds the applied magnitude**
    - **Validates: Requirements 22.3, 22.4, 22.6, 49.4, 49.10, 49.11**

  - [x] 13.8 Write property test for out-of-range values moving only toward safe
    - **Property 50: Out-of-range starting values only move toward the safe range**
    - **Validates: Requirements 22.5**

  - [x] 13.9 Write property test for Safety_Boundary resolution precedence
    - **Property 51: Safety_Boundary resolution precedence**
    - **Validates: Requirements 22.11, 49.11**

  - [x] 13.10 Write property test for the approval gate triggering below the maximum
    - **Property 52: Approval gate triggers below the maximum**
    - **Validates: Requirements 22.8, 49.5, 49.19, 4.1**

  - [x] 13.11 Write property test for non-silent personality change
    - **Property 53: Personality change is never silent and never auto-executes all**
    - **Validates: Requirements 49.8, 49.12**

  - [x] 13.12 Write property test for hosting pause/turn-off behavior
    - **Property 54: Hosting pause/turn-off stops new ops and resolves in-flight ones**
    - **Validates: Requirements 22.9, 22.12**

  - [x] 13.13 Write property test for capability phase gating
    - **Property 55: Capability phase gating**
    - **Validates: Requirements 22.1, 22.2, 49.4, 54.1, 54.2, 54.3, 54.4**

- [x] 14. Implement recommendation, search-term, goal, and engine correctness
  - [x] 14.1 Implement the pure `RecommendationStatusMapper`
    - Map Sync_State → Recommendation_Status: every Unsettled_State → `applying`; `effective`→`effective`; `failed`→`failed`; `cancelled`/`superseded`→`pending`; `local-only`→`local-only`; no applied Operation → `pending`; never `expired`→`failed`
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8_

  - [x] 14.2 Reject unsupported Recommendation_Type on apply
    - Reject with an error naming the unsupported type; leave the Recommendation in `pending`
    - _Requirements: 11.1, 11.2_

  - [x] 14.3 Implement `SearchTermHarvestService`
    - Derive target campaign/ad group from the term; apply `add_exact`/`add_phrase` (enabled keyword), `add_negative` (Negative_Keyword, never a positive keyword), `watchlist` (watch record only); validate override targets against scope; reject invalid/unresolvable actions
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5, 13.6, 13.7, 13.8_

  - [x] 14.4 Implement `GoalMetricsService`
    - Scope metrics to the goal's campaigns; on update propagate ONLY target ACoS + optimization goal, never name/budget/personality and never overwrite a Campaign-level override
    - _Requirements: 20.1, 20.2, 20.3_

  - [x] 14.5 Harden `RecommendationEngineService`
    - Null-safe; ad-group default-bid fallback (skip when neither exists); zero-sales waste rule; duplicate suppression; target-ACoS resolution Goal → product → configurable default 0.25; store-scoped
    - _Requirements: 18.1, 18.2, 18.3, 18.4, 18.5, 18.6, 18.8_

  - [x] 14.6 Scope Smart_Diagnosis to a parent ASIN
    - Include only campaigns/records associated with the parent ASIN; reuse the engine's duplicate suppression
    - _Requirements: 46.1, 46.2, 46.3, 46.4_

  - [x] 14.7 Implement the pure `AcosScale` and per-column migration
    - Decimal-ratio storage/compare, ×100 display across DB/API/filter/form; migrate per column with an ambiguity exception list
    - _Requirements: 17.1, 17.2, 17.3, 17.4, 17.5, 17.7_

  - [x] 14.8 Implement the pure `ObjectStatusVocabulary` and migration
    - Normalize `active→enabled`, `paused→paused`, `archived→archived`; write unknown values to the exception list, never auto-map
    - _Requirements: 16.1, 16.5, 16.6, 16.7_

  - [x] 14.9 Implement `OptimizationGoalMigration` and exception lists
    - Map legacy goal-type/hosting-goal to the new enum; write unmappable values to the exception list
    - _Requirements: 57.1, 57.2, 57.3_

  - [x] 14.10 Implement period-over-period trend, structured savings, and AI estimates
    - Compute growth against the immediately preceding equal-length period (not-available when no baseline); derive savings from structured currency-normalized amounts (never parsed from text); compute AI estimates with baseline/window/confidence/algorithm version and the fixed "暂不可估算：缺少有效基线" signal when not estimable
    - _Requirements: 19.2, 19.3, 19.4, 19.5, 19.6, 19.7, 19.8, 50.12_

  - [x] 14.11 Implement Marketplace_Timezone day-boundary computation
    - Compute "today"/day boundaries/date ranges/scheduling in the Active_Store's Marketplace_Timezone; reject with a configuration error when the timezone is unset (never default to server timezone)
    - _Requirements: 30.4, 51.13, 51.14_

  - [x] 14.12 Write property test for Recommendation_Status mapping
    - **Property 26: Recommendation_Status mapping is total and exact**
    - **Validates: Requirements 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8**

  - [x] 14.13 Write property test for unsupported Recommendation_Type rejection
    - **Property 27: Unsupported Recommendation_Type is rejected, not applied**
    - **Validates: Requirements 11.1, 11.2**

  - [x] 14.14 Write property test for harvest action output
    - **Property 31: Harvest action determines the produced object**
    - **Validates: Requirements 13.3, 13.4, 13.5, 13.6, 13.7**

  - [x] 14.15 Write property test for harvest target derivation and scoped override
    - **Property 32: Harvest target derivation and scoped override**
    - **Validates: Requirements 13.1, 13.2**

  - [x] 14.16 Write property test for Object_Status vocabulary normalization
    - **Property 38: Object_Status vocabulary normalization with exception list**
    - **Validates: Requirements 16.1, 16.5, 16.6, 16.7**

  - [x] 14.17 Write property test for the ACoS decimal-ratio scale
    - **Property 39: ACoS decimal-ratio scale is consistent across layers**
    - **Validates: Requirements 17.1, 17.2, 17.3, 17.4, 17.7**

  - [x] 14.18 Write property test for migration never guessing unknown values
    - **Property 40: Migration never guesses unknown or ambiguous values**
    - **Validates: Requirements 17.5, 17.6, 16.6, 57.1, 57.2, 57.3**

  - [x] 14.19 Write property test for recommendation engine robustness and resolution
    - **Property 41: Recommendation engine robustness and resolution**
    - **Validates: Requirements 18.1, 18.2, 18.3, 18.4, 18.6**

  - [x] 14.20 Write property test for dedup and store-scoping
    - **Property 42: Recommendation generation is deduplicated and store-scoped**
    - **Validates: Requirements 18.5, 18.8, 46.3, 46.4**

  - [x] 14.21 Write property test for smart diagnosis scoping
    - **Property 43: Smart diagnosis is scoped to its parent ASIN**
    - **Validates: Requirements 46.1, 46.2**

  - [x] 14.22 Write property test for period-over-period trend
    - **Property 44: Period-over-period trend uses the immediately preceding equal-length period**
    - **Validates: Requirements 19.4, 19.5**

  - [x] 14.23 Write property test for structured savings derivation
    - **Property 45: Savings are derived from structured multi-currency-normalized amounts**
    - **Validates: Requirements 19.2, 19.3**

  - [x] 14.24 Write property test for honest AI estimates
    - **Property 46: AI estimates carry their method and degrade honestly**
    - **Validates: Requirements 19.6, 19.7, 19.8, 50.12**

  - [x] 14.25 Write property test for goal metric scoping and whitelisted propagation
    - **Property 47: Goal metrics are scoped and propagation is whitelisted**
    - **Validates: Requirements 20.1, 20.2, 20.3**

  - [x] 14.26 Write property test for day-boundary timezone computation
    - **Property 74: Day boundaries are computed in the Marketplace_Timezone**
    - **Validates: Requirements 30.4, 51.13, 51.14**

- [x] 15. Revise the canonical API schema and machine values
  - [x] 15.1 Revise `KeywordVo`, `SearchTermVo`, `GoalVo` and their converters
    - Flatten metrics + `bidHealthScore`; add `harvestingStatus`/`periodStart`/`periodEnd`/single `cpc`; add `campaigns`/`products`/`trendData`/`campaignCount`
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5_

  - [x] 15.2 Enforce machine-value-only enums in advertising responses
    - Return only `Machine_Value_Enum` values for AI_Hosting_Status, AI_Personality, Optimization_Goal, Object_Status; never Chinese display strings
    - _Requirements: 14.7, 48.4_

  - [x] 15.3 Gate synced-list inclusion in the campaign list response
    - Include a Campaign in the Amazon-synced list iff it has a non-empty `amazon_campaign_id` AND its creation Operation reached `effective`; otherwise expose it only in local-drafts / with a local-source badge
    - _Requirements: 12.7, 12.8_

  - [x] 15.4 Write property test for GoalVo campaignCount consistency
    - **Property 33: GoalVo campaignCount equals its campaigns collection length**
    - **Validates: Requirements 14.4**

  - [x] 15.5 Write property test for machine-value-only emission
    - **Property 34: Backend emits only machine-value enums**
    - **Validates: Requirements 14.7, 48.4**

  - [x] 15.6 Write property test for synced-list inclusion gating
    - **Property 29: Synced-list inclusion is gated by effective creation and amazon_campaign_id**
    - **Validates: Requirements 12.7, 12.8**

- [x] 16. Enforce the permission matrix and data scope across advertising
  - [x] 16.1 Apply the Requirement 27.1 permission matrix to advertising endpoints
    - Annotate each advertising controller action with exactly the required permission code (incl. new `advertising:execute`); reject with HTTP 403 when missing; expose no interactive delete permission for audit resources (Operation, SyncLog, BidChange)
    - _Requirements: 24.2, 24.3, 27.1, 27.3, 27.4, 27.5, 27.6_

  - [x] 16.2 Apply data-scope + per-record ownership uniformly
    - Resolve the acting user from the security context; reject any cross-store/out-of-scope reference (including guessed ids and any cross-store member of a bulk request) with HTTP 403 without disclosing contents
    - _Requirements: 24.1, 24.4, 25.1, 25.2, 25.3, 25.4_

  - [x] 16.3 Write property test for permission-matrix enforcement
    - **Property 56: Permission matrix enforcement**
    - **Validates: Requirements 24.2, 24.3, 27.1, 27.3, 27.4, 27.5, 27.6**

  - [x] 16.4 Write property test for data scope and ownership
    - **Property 57: Data scope and ownership prevent cross-store access**
    - **Validates: Requirements 24.1, 24.4, 25.1, 25.2, 25.3, 25.4**

- [x] 17. Checkpoint - backend complete
  - Ensure all tests pass, ask the user if questions arise.

- [x] 18. Build the frontend query-state and data-fetching layer
  - [x] 18.1 Implement `useAdvertisingQueryState` with URL encode/decode
    - Single source of truth for filters/date-range/sort/pagination shared by FilterToolbar, KpiPanel, and SharedDataTable; implement pure `encodeFilters`/`decodeFilters` round-trip to/from the URL
    - _Requirements: 34.4, 34.5, 39.1, 39.2, 39.3_

  - [x] 18.2 Wire the advertising react-query hooks (Data_Fetching_Layer)
    - Add react-query hooks reading from `useAdvertisingQueryState` for the advertising list/detail endpoints
    - _Requirements: 39.1, 39.2, 39.3_

  - [x] 18.3 Write property test for the single source of truth
    - **Property 61: Single source of truth for query conditions**
    - **Validates: Requirements 39.1, 39.2, 39.3**

  - [x] 18.4 Write property test for the URL filter/sort round-trip
    - **Property 62: URL filter/sort round-trip**
    - **Validates: Requirements 34.4, 34.5**

- [x] 19. Complete the advertising data-table capabilities
  - [x] 19.1 Wire `SharedDataTable` for advertising
    - Pass `total`/`tableKey`; add server-side pagination control; ensure server-side filters constrain the full result set and reject non-persisted filter fields; ensure pagination+sort return the correct global slice
    - _Requirements: 15.1, 15.2, 15.3, 15.6, 15.7, 31.x, 33.1, 33.4_

  - [x] 19.2 Add column management, export, and saved-view entries
    - Wire column-management UI; export "all filtered rows" vs "current page" containing only currently visible columns; saved-view entry scoped to `(user_id, table_key)`
    - _Requirements: 31.x, 32.2, 34.3, 37.2, 37.3, 37.4_

  - [x] 19.3 Implement pinned-column offsets and currency-aware formatting
    - Cumulative, non-overlapping pinned offsets via a pure `resolvePinnedOffsets`; format each monetary value in its own currency (multi-currency views included)
    - _Requirements: 33.2, 38.1, 38.2, 38.3_

  - [x] 19.4 Implement fixed status-filter enumerations
    - Each status filter offers the fixed enumeration independent of the loaded rows
    - _Requirements: 35.1, 35.2, 35.3_

  - [x] 19.5 Implement cross-page selection semantics
    - "select current page" = rendered rows only; "select all filtered results" = all matching across pages with covered count; apply bulk op to every matching record
    - _Requirements: 44.1, 44.2, 44.3, 44.4_

  - [x] 19.6 Implement explicit UI states and in-progress control disabling
    - Resolve each view to exactly one of loading/empty/partial-error/stale-cache/no-permission/content via a pure `resolveViewState`; disable an in-progress control until completion
    - _Requirements: 36.3, 41.1, 41.2, 41.3, 41.4, 41.5_

  - [x] 19.7 Write property test for server-side filters
    - **Property 36: Server-side filters constrain the full result set**
    - **Validates: Requirements 15.1, 15.2, 15.3, 15.6, 15.7**

  - [x] 19.8 Write property test for server-side pagination and sort
    - **Property 37: Server-side pagination and sort return the correct global slice**
    - **Validates: Requirements 33.1, 33.4**

  - [x] 19.9 Write property test for saved-view isolation
    - **Property 63: Saved view isolation**
    - **Validates: Requirements 34.3**

  - [x] 19.10 Write property test for result count equals server total
    - **Property 64: Result count equals server total**
    - **Validates: Requirements 32.2**

  - [x] 19.11 Write property test for fixed status enumerations
    - **Property 65: Status filter options come from a fixed enumeration**
    - **Validates: Requirements 35.1, 35.2, 35.3**

  - [x] 19.12 Write property test for multi-pinned-column offsets
    - **Property 66: Multi-pinned-column offsets are cumulative and non-overlapping**
    - **Validates: Requirements 38.1, 33.2**

  - [x] 19.13 Write property test for currency formatting
    - **Property 67: Monetary values are formatted in their own currency**
    - **Validates: Requirements 38.2, 38.3**

  - [x] 19.14 Write property test for cross-page selection semantics
    - **Property 68: Cross-page selection semantics**
    - **Validates: Requirements 44.1, 44.2, 44.3, 44.4**

  - [x] 19.15 Write property test for export scope and visible columns
    - **Property 70: Export covers the chosen scope and visible columns only**
    - **Validates: Requirements 37.2, 37.3, 37.4**

  - [x] 19.16 Write property test for explicit UI-state resolution
    - **Property 71: Every view resolves to exactly one explicit UI state**
    - **Validates: Requirements 41.1, 41.2, 41.3, 41.4, 41.5**

  - [x] 19.17 Write property test for in-progress double-submission prevention
    - **Property 72: In-progress controls prevent double submission**
    - **Validates: Requirements 36.3**

- [x] 20. Build the advertising navigation and context shell
  - [x] 20.1 Implement `AdvertisingWorkspace` four-group navigation
    - Present the four groups (广告管理 / 搜索词管理 / 智能优化 / 记录与审计) over the eleven existing tabs as a total partition with no tab removed
    - _Requirements: 28.1, 28.2, 28.4_

  - [x] 20.2 Implement `ContextBar` and `FilterChips`
    - ContextBar shows store/site/account/currency/data-date/last-sync; render exactly one chip per applied filter (none when empty); removing a chip re-requests with remaining filters
    - _Requirements: 29.1, 29.3, 29.4, 29.5_

  - [x] 20.3 Implement the collapsible `KpiPanel`
    - Default last-7-days vs prior-7-days comparison in Marketplace_Timezone
    - _Requirements: 30.1, 30.4_

  - [x] 20.4 Implement context preservation, store-switch/unsaved-edit guards, and responsive strategy
    - Restore originating tab filters and selection (for present rows) and preserve unsaved edits across tab switches; guard store-switch and unsaved edits; render full desktop table ≥768px and a read-oriented view/pause/approve alternative below 768px
    - _Requirements: 40.x, 43.2, 43.3, 43.4, 45.1, 45.2, 45.3_

  - [x] 20.5 Write property test for the tab-to-group partition
    - **Property 59: Tab-to-group assignment is a total partition**
    - **Validates: Requirements 28.1, 28.2, 28.4**

  - [x] 20.6 Write property test for filter chips
    - **Property 60: Filter chips correspond exactly to applied filters**
    - **Validates: Requirements 29.3, 29.4, 29.5**

  - [x] 20.7 Write property test for context-preservation round-trip
    - **Property 69: Context preservation round-trip across tab switches**
    - **Validates: Requirements 45.1, 45.2, 45.3**

  - [x] 20.8 Write property test for the responsive threshold
    - **Property 73: Responsive threshold switching at 768px**
    - **Validates: Requirements 43.2, 43.3, 43.4**

- [x] 21. Build operation actions, translation, and the AI hosting UI
  - [x] 21.1 Implement the pure `actionsForSyncState` and `OperationActions`
    - Render Approve/Reject/Cancel/Retry/Reconcile/Undo strictly per the Requirement 56.1 matrix and nothing outside it; use distinct copy for "关闭AI托管" vs "取消平台操作"
    - _Requirements: 8.3, 21.4, 48.7, 56.1, 56.2_

  - [x] 21.2 Implement the total `translateMachineValue` mapping
    - Map every Machine_Value_Enum value to a defined display string (托管中/未托管, 常规型/平衡型/激进型, optimization-goal copy, enabled/paused/archived)
    - _Requirements: 3.6, 48.5_

  - [x] 21.3 Implement permission-driven control visibility
    - Show/enable an action control iff the permission set contains the matrix code; a hidden/disabled control never issues its backend request
    - _Requirements: 26.1, 26.2, 26.3, 26.4, 27.7_

  - [x] 21.4 Build the AI hosting UI
    - Settings drawer ordered sections; three-segment personality control with 人格影响预览; resolved-personality column with inherited/override indicator; overview first-screen figures; 人格分布; decision explanations; fix `CreateGoalPage` so selecting a goal/targeting type never silently switches personality
    - _Requirements: 49.8, 50.x_

  - [x] 21.5 Write property test for machine-value-to-display translation totality
    - **Property 35: Frontend machine-value-to-display translation is total**
    - **Validates: Requirements 48.5, 3.6**

  - [x] 21.6 Write property test for permission-driven control visibility
    - **Property 58: Frontend control visibility follows the permission matrix**
    - **Validates: Requirements 26.1, 26.2, 26.3, 26.4, 27.7**

  - [x] 21.7 Write property test for the state-to-actions matrix
    - **Property 79: State-to-actions matrix is exact**
    - **Validates: Requirements 56.1, 56.2, 8.3, 21.4**

- [x] 22. Final checkpoint - full system
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional test sub-tasks (property, unit, integration) and can be skipped for a faster MVP; core implementation tasks are never optional.
- Each property test is a single test running a minimum of 100 iterations, tagged `Feature: advertising-workspace-rework, Property {N}` — backend with jqwik, frontend with fast-check + Vitest.
- Each task references specific requirement sub-clauses for traceability; checkpoints provide incremental validation.
- This rework reconciles with `core-platform-completion`, `app-functionality-completion`, `platform-ux-logistics-enhancements`, and `project-fix-and-cleanup` rather than redefining their owned surfaces.
- Long-running commands (dev servers, watch-mode test runners) should be run manually; use single-run test invocations during verification.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1", "3.1"] },
    { "id": 1, "tasks": ["2.2", "2.4", "3.2", "4.1"] },
    { "id": 2, "tasks": ["1.2", "2.3", "2.5", "3.3", "4.2", "4.3", "4.4", "5.1", "5.2", "5.3", "5.4"] },
    { "id": 3, "tasks": ["6.1", "6.2", "6.3", "8.1", "5.5", "5.6", "5.7", "5.8", "5.9", "5.10"] },
    { "id": 4, "tasks": ["7.1", "7.2", "7.3", "7.4", "7.5", "7.6", "7.7", "7.8", "6.4", "6.5", "6.6", "6.7", "6.8", "8.2"] },
    { "id": 5, "tasks": ["10.1", "10.2", "10.3", "10.4", "10.5", "11.1", "11.2", "7.9", "7.10", "7.11", "7.12", "7.13", "7.14", "7.15"] },
    { "id": 6, "tasks": ["13.1", "13.2", "13.3", "13.4", "14.1", "14.2", "14.3", "14.4", "14.5", "14.6", "14.7", "14.8", "14.9", "14.10", "14.11", "10.6", "10.7", "10.8", "10.9", "10.10", "10.11", "10.12", "10.13", "10.14", "10.15", "11.3"] },
    { "id": 7, "tasks": ["13.5", "15.1", "15.2", "15.3", "16.1", "16.2", "13.6", "13.7", "13.8", "13.9", "13.10", "13.11", "13.12", "13.13", "14.12", "14.13", "14.14", "14.15", "14.16", "14.17", "14.18", "14.19", "14.20", "14.21", "14.22", "14.23", "14.24", "14.25", "14.26"] },
    { "id": 8, "tasks": ["18.1", "18.2", "15.4", "15.5", "15.6", "16.3", "16.4"] },
    { "id": 9, "tasks": ["19.1", "19.2", "19.3", "19.4", "19.5", "19.6", "20.1", "20.2", "20.3", "20.4", "21.1", "21.2", "21.3", "21.4", "18.3", "18.4"] },
    { "id": 10, "tasks": ["19.7", "19.8", "19.9", "19.10", "19.11", "19.12", "19.13", "19.14", "19.15", "19.16", "19.17", "20.5", "20.6", "20.7", "20.8", "21.5", "21.6", "21.7"] }
  ]
}
```
