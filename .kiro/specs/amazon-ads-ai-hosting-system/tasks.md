# Implementation Plan: Amazon Ads AI Hosting System

## Overview

This plan converts the design into incremental, test-driven coding tasks for the
Java/Spring backend (property tests via **jqwik**, matching the existing
`TableViewIsolationPropertyTest` / `HostingBidOptimizer*Propert*` tests) and the
React + TypeScript frontend (pure-logic property tests via **fast-check**).

The work is deliberately additive: it reuses `OperationService`, `OutboxWorker`,
`StatusPoller`, `OperationStateMachine`, `SafetyBoundaryResolver`, and `FeishuService`,
and layers on the Amazon Ads connector, the async report/entity sync, the data-quality
gate, the V1/V2/V3 engines + coordinator, risk/execution/routing, decisions + immutable
snapshot, attribution, rollback, notifications, governance, APIs, and the dashboard.

Each correctness property (1–48) from the design is implemented by exactly one
property-based test sub-task, placed next to the code it validates, annotated with its
property number and the requirements clause it checks. Property/unit/integration test
sub-tasks are marked optional with `*`.

## Tasks

- [x] 1. Schema, state machine, and SPI foundation
  - [x] 1.1 Apply schema changes to existing tables
    - In `db/schema.sql`: rename `performance_daily.date` → `report_date`; add `currency`, `data_status` (CHECK preliminary/finalized), `data_version`, `updated_at`; add `uq_perf_daily UNIQUE(store_id, entity_type, entity_id, report_date)`
    - Add `uq_eem_external UNIQUE(store_id, platform, external_entity_type, external_entity_id)` to `external_entity_mappings`
    - Add `next_attempt_at`, `last_error` columns to `operation_outbox`
    - Add `scope_id`, `status`, `effective_from`, `effective_to` to `personality_policies`; drop old unique, add `uq_pp_scope UNIQUE(scope, scope_id, personality, rule_version)`
    - _Requirements: 16.7, 32.1, 32.2, 32.3, 32.4, 32.5, 14.3, 38.1_
  - [x] 1.2 Create new hosting tables
    - In `db/schema.sql` add: `report_sync_runs`, `report_sync_errors`, `search_term_daily`, `metric_quarantine`, `safety_boundaries`, `brand_word_lists`, `ai_decisions`, `effect_attributions`, `optimization_runs`, `notification_delivery_log`, `hosting_kill_switches`, `hosting_configs` with the columns and unique constraints from the design
    - _Requirements: 2.7, 31.1, 31.2, 14.6, 6.7, 22.1, 37.1, 8.3, 18.1, 9.6, 9.7, 35.3, 12.1, 21.1_
  - [x] 1.3 Extend SyncState and OperationStateMachine
    - Add `SUBMITTING` to `SyncState`; add transitions `pending → submitting → submitted` and `submitting → pending` on retryable; change the `APPROVE` edge to `awaiting_approval → pending` and keep `REJECT → cancelled`; reserve `failed → pending` for manual retry only
    - _Requirements: 16.7, 7.7, 7.8, 24.2_
  - [x] 1.4 Write property test for approval/reject state edges
    - **Property 22: Approve routes to pending, reject to cancelled**
    - **Validates: Requirements 7.7, 7.8, 24.2**
  - [x] 1.5 Extend the write SPI with structured results and verification
    - Extend `PlatformWriteResult` record with `amazonRequestId`, `externalEntityId`, `platformErrorCode`, `retryable`, `retryAfterSeconds`; keep existing 3-arg factories and add `acceptedAmazon`, `retryable`, `permanentReject`
    - Add `PlatformVerifyResult` record, `SubmissionMetadata` record, and the `verify(ctx, change, meta)` default SPI method returning `unsupported()`; declare the deferred `submitBatch` extension point
    - _Requirements: 1.11, 1.12, 1.8, 1.9, 16.5, 16.6, 16.3_
  - [x] 1.6 Write schema-shape and bean smoke tests
    - Verify new tables/columns exist and connector/engine beans register
    - _Requirements: 1.1, 6.1, 14.1, 14.3, 22.1, 31.2, 32.2, 32.3, 32.5, 37.1_

- [x] 2. Amazon Ads connector core (Phase 2)
  - [x] 2.1 Implement AmazonAdsTokenService
    - Per-`PlatformConnection` LWA access-token cache with proactive refresh (5 min before expiry), `refresh_token` decryption via `CryptoUtil`; on `invalid_grant` mark connection `token_expired`, stop calls, flag degraded write capability, trigger Feishu alert
    - _Requirements: 1.7, 1.15, 15.3, 15.4_
  - [x] 2.2 Implement AmazonAdsRateLimiter
    - Per-profile token bucket (default 10 req/s), `nextAvailableInstant()` consulted by the Outbox, Redis-backed with in-memory/DB fallback, adaptive backoff (>10 rate-limits in 5 min → -50% for 15 min); never blocks the caller thread
    - _Requirements: 15.1, 15.2, 15.6, 30.3_
  - [x] 2.3 Write property test for the rate limiter
    - **Property 7: Rate limiter never blocks**
    - **Validates: Requirements 15.1, 15.2**
  - [x] 2.4 Implement AmazonAdsWriteConnector.submit
    - Register Spring bean `platform() == "amazon_ads"`; route by `changeType` (bid/budget/state/keyword/negative_keyword); resolve external id from `external_entity_mappings` (reject `NO_EXTERNAL_MAPPING`); exactly one HTTP call per submit; map 2xx→accepted (structured request id + external id), 429→retryable+retryAfter, 500/502/503→retryable+backoff, 400/422→permanentReject with error code, invalid token→`TOKEN_INVALID`; log via `PlatformLogSanitizer`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.10, 1.11, 1.12, 14.2, 16.1, 16.2, 15.5_
  - [x] 2.5 Write unit tests for per-change-type routing
    - Cover bid, budget, state, keyword, negative-keyword endpoint mapping and idempotency controls
    - _Requirements: 1.2, 1.3, 1.4, 1.5, 1.6, 1.10_
  - [x] 2.6 Write property test for connector response classification
    - **Property 1: Connector response classification**
    - **Validates: Requirements 1.8, 1.9, 1.11, 1.12, 16.5, 30.7**
  - [x] 2.7 Write property test for single submit per change
    - **Property 5: Single submit per change**
    - **Validates: Requirements 16.1, 16.2, 16.4**
  - [x] 2.8 Write property test for required external mapping
    - **Property 6: External mapping required for submission**
    - **Validates: Requirements 14.2**
  - [x] 2.9 Implement connector verify() (read-after-write)
    - Re-read the changed entity field via the Amazon Ads API and return a structured `PlatformVerifyResult`; never map lifecycle state strings to the changed value unless lifecycle is the changed field
    - _Requirements: 1.13, 1.14, 16.6, 20.2, 20.3_
  - [x] 2.10 Write property test for verification mapping
    - **Property 2: Read-after-write verification mapping**
    - **Validates: Requirements 1.13, 1.14, 16.6, 20.2, 20.3**

- [x] 3. Outbox retry ownership and verification worker
  - [x] 3.1 Make the Outbox the single retry owner
    - On `retryable=true` increment `attempt_count`, set `next_attempt_at = now + retryAfter`, record `last_error`, leave Operation pre-submission; advance to `submitted` only on accepted; permanent reject → `failed`; check Amazon circuit breaker before claiming; ensure the Outbox row exists at approval time
    - _Requirements: 16.7, 30.1, 30.7_
  - [x] 3.2 Write property test for acceptance-gated submission and retry reuse
    - **Property 3: Submitted only after acceptance; transport retries reuse the same Operation**
    - **Validates: Requirements 16.7, 30.1, 30.7**
  - [x] 3.3 Implement VerificationWorker
    - After configurable delay invoke connector `verify`; map match→`effective`, mismatch→`reconciliation_required`, read fail/unchanged past retries→`expired` with reason `VERIFY_TIMEOUT`; keep `expired` eligible for later `effective`/`failed`/`reconciliation_required`
    - _Requirements: 20.1, 20.4_
  - [x] 3.4 Write property test for non-terminal verify timeout
    - **Property 4: Verify timeout is non-terminal**
    - **Validates: Requirements 20.4**

- [x] 4. Checkpoint - connector and write/verify path
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Amazon Ads report sync (Phase 1)
  - [x] 5.1 Implement ReportLifecycleClient
    - Async reporting lifecycle per report type (SP campaign/keyword/search term): create → poll until COMPLETED/FAILED/EXPIRED → fetch download URL → download → gunzip → validate reportId, date range, row count
    - _Requirements: 2.2_
  - [x] 5.2 Implement report ingestion and quarantine
    - Idempotent upsert into `performance_daily` keyed by `(store_id, entity_type, entity_id, report_date)` and `search_term_daily` by its key; derive `data_status` from age vs `finalizationLagDays`; bump `data_version` on changed backfill; resolve external ids via `external_entity_mappings`; route unresolved rows to `metric_quarantine` without fabricating entities
    - _Requirements: 2.3, 2.4, 2.5, 31.1, 32.1, 32.4_
  - [x] 5.3 Write property test for idempotent metric upsert
    - **Property 8: Idempotent metric upsert**
    - **Validates: Requirements 2.4, 31.1, 32.1**
  - [x] 5.4 Write property test for data-status derivation
    - **Property 9: Data status is a pure function of age**
    - **Validates: Requirements 2.4, 32.4**
  - [x] 5.5 Write property test for backfill version bump
    - **Property 10: Backfill bumps data version**
    - **Validates: Requirements 2.4**
  - [x] 5.6 Write property test for quarantine of unresolved rows
    - **Property 11: Unresolved metric rows are quarantined, never fabricated into entities**
    - **Validates: Requirements 2.3, 2.5, 14.4, 14.6**
  - [x] 5.7 Implement ReportSyncJob cadences, ledger, and gap detection
    - Three cadences (intra-day hourly, daily 06:00 marketplace tz with finalization promotion, rolling 7–14 day backfill); persist each run in `report_sync_runs`; gap detection vs `expectedFinalizedDate`; retry 3× with exponential backoff recording failures in `report_sync_errors`; send Feishu data-gap notification on >2-day finalized gap
    - _Requirements: 2.1, 2.6, 2.7, 2.8_
  - [x] 5.8 Write property test for finalized-coverage gap detection
    - **Property 12: Finalized-coverage gap detection**
    - **Validates: Requirements 2.8**
  - [x] 5.9 Write integration test for the async reporting lifecycle
    - Mock Amazon Ads API: create→poll→download→ingest end to end
    - _Requirements: 2.2_

- [x] 6. Amazon Ads entity sync
  - [x] 6.1 Implement EntitySyncJob
    - Pull campaign/ad-group/keyword metadata, upsert complete local entities + `external_entity_mappings` (origin `amazon_import`); run before/independently of metric ingestion; update mapping with Amazon-assigned id on read-after-write of locally-created entities
    - _Requirements: 14.1, 14.4, 14.5_
  - [x] 6.2 Write unit tests for entity-sync mapping updates
    - _Requirements: 14.5_

- [x] 7. Data quality gate and pre-submission revalidation (Phase 3)
  - [x] 7.1 Implement DataQualityGate
    - Freshness check (default 48h); completeness judged against `report_sync_runs` finalized coverage (not non-empty row counts); fail-closed on DB error/missing coverage; return `DataQualityResult(passed, reason)` with `DATA_STALE`/`DATA_INCOMPLETE`; batch rejected campaigns into next Feishu digest
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 6.6, 25.5, 30.2_
  - [x] 7.2 Write property test for the data-quality gate decision
    - **Property 13: Data quality gate decision**
    - **Validates: Requirements 3.1, 3.2, 3.5, 6.6, 25.5, 30.2**
  - [x] 7.3 Implement decision expiry and pre-submission revalidation
    - Set `decision_expires_at`; before submission re-run DQ gate and re-resolve boundaries; on failure transition Operation to `superseded`/`expired` with reason recorded in run log and audit trail
    - _Requirements: 3.7, 33.1, 33.2, 33.3, 33.4, 33.5_
  - [x] 7.4 Write property test for stale/expired decisions never submitting
    - **Property 14: Stale or expired decisions are never submitted**
    - **Validates: Requirements 3.7, 33.2, 33.3, 33.4**

- [x] 8. Enhanced safety boundaries (Phase 4)
  - [x] 8.1 Extend boundary model and persistence
    - Extend `SafetyBoundaryLimit` with all Requirement 6.1 limits; add `BoundaryComparison` enum and per-limit `more_restrictive(a,b)` operator (upper/lower bound, boolean OR, set intersection); persist typed values in `safety_boundaries`
    - _Requirements: 6.1, 6.2, 6.7_
  - [x] 8.2 Write property test for the more-restrictive operator
    - **Property 15: Per-limit more-restrictive operator**
    - **Validates: Requirements 6.2**
  - [x] 8.3 Upgrade SafetyBoundaryResolver to most-restrictive-wins
    - Fold each level's value through `more_restrictive` across the 5-level hierarchy campaign → goal → store → organization → system
    - _Requirements: 6.4, 21.4_
  - [x] 8.4 Write property test for only-tightening resolution
    - **Property 16: Boundary resolution only tightens**
    - **Validates: Requirements 6.4, 21.4**
  - [x] 8.5 Implement SafetyBoundaryValidator
    - Reject a looser lower-level value (identify the constraining higher-level boundary); enforce cross-field constraints (minBid ≤ maxBid, minDailyBudget ≤ maxDailyBudget, inventoryCriticalDays ≤ inventorySafetyDays ≤ inventoryHealthyDays); audit changes with before/after and actor
    - _Requirements: 6.3, 6.8, 12.6_
  - [x] 8.6 Write property test for only-tighten configuration rejection
    - **Property 17: Only-tighten configuration rejection**
    - **Validates: Requirements 6.3, 21.4**
  - [x] 8.7 Write property test for cross-field validation
    - **Property 18: Boundary cross-field validation**
    - **Validates: Requirements 6.8, 12.6**

- [x] 9. Checkpoint - data quality and safety boundaries
  - Ensure all tests pass, ask the user if questions arise.

- [x] 10. Risk, execution mode, and routing pipeline
  - [x] 10.1 Implement RiskScoreCalculator
    - Deterministic, versioned 0.0–1.0 score from change magnitude, data confidence, historical volatility, absolute dollar impact; record `formula_version`
    - _Requirements: 7.1, 7.9_
  - [x] 10.2 Write property test for the risk score
    - **Property 19: Risk score is bounded, deterministic, and monotonic**
    - **Validates: Requirements 7.1, 7.9**
  - [x] 10.3 Implement ExecutionModeResolver
    - Resolve `observe_only|recommend_only|approval_required|auto_execute` by campaign > goal > store inheritance; default `observe_only`
    - _Requirements: 7.2_
  - [x] 10.4 Write property test for execution-mode resolution
    - **Property 20: Execution mode resolution**
    - **Validates: Requirements 7.2**
  - [x] 10.5 Implement HighRiskClassifier and DecisionRoutingPipeline
    - Fixed precedence Kill Switch → Shadow → Phase → Execution Mode → High-Risk Gate → Risk Threshold; produce no-op / `ai_decisions`-only / `pending` / `awaiting_approval`; pipeline never applies SUBMIT
    - _Requirements: 7.3, 7.4, 7.5, 7.10, 35.1, 35.3, 37.2, 37.3_
  - [x] 10.6 Write property test for the routing pipeline
    - **Property 21: Fixed-precedence routing pipeline**
    - **Validates: Requirements 7.3, 7.4, 7.5, 7.10, 35.1, 35.3, 37.2, 37.3**
  - [x] 10.7 Integrate approval routing
    - Create `approval_requests` for `awaiting_approval`; atomic approval status + Operation transition; reject self-approval
    - _Requirements: 24.2, 24.3, 40.2, 40.4_
  - [x] 10.8 Write property test for approval atomicity and no self-approval
    - **Property 23: Approval atomicity and no self-approval**
    - **Validates: Requirements 40.2, 40.4**

- [x] 11. AI decision storage and immutable snapshot
  - [x] 11.1 Implement ai_decisions store and DecisionSnapshot
    - Entity/mapper/service for `ai_decisions` (persist every decision regardless of mode, `promoted_operation_id` link); immutable `DecisionSnapshot` value object serialized via the existing JSON codec, written once at creation onto `ai_decisions.decision_snapshot` and the promoted Operation
    - _Requirements: 34.1, 34.2, 34.3, 34.4, 37.1, 37.2, 37.3, 37.5, 13.6_
  - [x] 11.2 Write property test for snapshot immutability and round-trip
    - **Property 35: Decision snapshot is immutable and round-trips**
    - **Validates: Requirements 34.2, 34.3, 37.5, 38.4, 13.6**

- [x] 12. Optimization snapshot and coordinator
  - [x] 12.1 Implement DataSnapshotProvider
    - Capture a single immutable point-in-time `DataSnapshot` at run start shared by all engines
    - _Requirements: 23.1, 23.2_
  - [x] 12.2 Write property test for the single immutable snapshot
    - **Property 27: Single immutable snapshot**
    - **Validates: Requirements 23.1, 23.2**
  - [x] 12.3 Implement OptimizationCoordinator
    - De-conflict candidates colliding with unsettled Operations; model cross-engine interactions; prioritize lowest-risk first; enforce `maxOperationsPerRun`/`maxOperationsPerDay`; apply safety-boundary clipping before handing survivors to the pipeline
    - _Requirements: 23.3, 23.4, 20.5, 36.5_
  - [x] 12.4 Write property test for operation caps
    - **Property 28: Coordinator enforces operation caps**
    - **Validates: Requirements 23.3**
  - [x] 12.5 Write property test for in-flight conflict de-duplication
    - **Property 29: In-flight conflict is never duplicated**
    - **Validates: Requirements 20.5, 23.4, 36.5**
  - [x] 12.6 Implement OptimizationRunService and run logging
    - Create/finalize `optimization_runs` (status, per-campaign results, skip reasons, counts); 90-day retention
    - _Requirements: 18.1, 18.2, 18.3, 18.4, 18.5_

- [x] 13. Checkpoint - decision core and coordination
  - Ensure all tests pass, ask the user if questions arise.

- [x] 14. V1 bid engine hardening (keyword-level)
  - [x] 14.1 Implement keyword-level V1 bid engine
    - Use each keyword's own ACoS over the lookback (no campaign-aggregate fallback); skip keywords with insufficient data; honor `adjustmentCooldownHours` at keyword grain and keyword-level in-flight lock; clamp within minBid/maxBid/maxCpc/maxBidAdjustmentRatio; emit candidates to the coordinator
    - _Requirements: 36.1, 36.2, 36.3, 36.4, 36.5, 36.6_
  - [x] 14.2 Write property test for keyword-level ACoS and bid clamp
    - **Property 26: V1 uses keyword-level ACoS and clamps the bid**
    - **Validates: Requirements 36.1, 36.2, 36.6**
  - [x] 14.3 Implement learning-period enforcement
    - Cap bid change magnitude at 10% and propose no budget changes during the learning period; restart the period when personality changes; expose days-remaining
    - _Requirements: 19.1, 19.2, 19.3, 19.4, 19.5_
  - [x] 14.4 Write property test for learning-period conservatism
    - **Property 33: Learning-period conservatism**
    - **Validates: Requirements 19.2, 19.4**

- [x] 15. V2 budget optimization engine (Phase 6)
  - [x] 15.1 Implement V2BudgetEngine
    - Compute candidate daily budget from target ACoS, actual ACoS over `lookbackDays`, spend velocity, inventory days; increase/decrease direction rules; clamp to min/max budget and increase/decrease ratios; honor cooldown and `NO_TARGET_ACOS` skip; gate on phase ≥ V2; emit candidates to the coordinator
    - _Requirements: 4.1, 4.2, 4.3, 4.5, 4.6, 4.7, 4.8, 4.9_
  - [x] 15.2 Write property test for V2 budget clamp and direction
    - **Property 25: V2 budget clamp and direction**
    - **Validates: Requirements 4.2, 4.3, 4.4, 4.5, 25.2**
  - [x] 15.3 Implement inventory-aware safety and EmergencyStopEvaluator
    - Force budget decrease below `inventorySafetyDays`; below `inventoryCriticalDays` apply internal AI kill switch immediately and emit a risk-0.9 reduction; boolean-OR emergency condition (spend>multiplier×budget OR ACoS>multiplier×target); fail-closed when inventory data unavailable
    - _Requirements: 4.4, 25.1, 25.2, 25.3, 25.4, 25.5, 6.5_
  - [x] 15.4 Write property test for inventory-critical response
    - **Property 34: Inventory-critical response**
    - **Validates: Requirements 25.3**

- [x] 16. V3 search-term harvest and keyword engine (Phase 8)
  - [x] 16.1 Implement V3 search-term scoring and proposals
    - Read exclusively from `search_term_daily`; score confidence from clicks/orders/ACoS/significance; propose exact-match keywords and negatives per thresholds; support exact/phrase/broad match types; respect `keywordExpansionMode`/`negativeKeywordMode` and per-day caps (queue overflow to next day); gate on phase V3; emit candidates recording confidence + evidence
    - _Requirements: 5.1, 5.2, 5.3, 5.5, 5.6, 5.7, 5.10, 5.11, 5.12, 31.4_
  - [x] 16.2 Implement brand-word protection
    - Check every negative candidate against the store `Brand_Word_List` (exact = full, contains = substring); hard-reject matches with reason `BRAND_PROTECTED`
    - _Requirements: 5.4, 22.3, 22.4_
  - [x] 16.3 Write property test for brand-word protection
    - **Property 30: Brand-word protection**
    - **Validates: Requirements 5.4, 22.3, 22.4**
  - [x] 16.4 Write property test for negative-keyword mode routing
    - **Property 31: V3 negative-keyword mode routing**
    - **Validates: Requirements 5.8, 5.9**
  - [x] 16.5 Mark reversibility on operations
    - Set `reversible=true` for keyword-bid and campaign-budget changes, `false` for keyword and negative-keyword additions
    - _Requirements: 5.11, 10.3_
  - [x] 16.6 Write property test for reversibility classification
    - **Property 32: Reversibility classification**
    - **Validates: Requirements 5.11, 10.3**

- [x] 17. Checkpoint - optimization engines
  - Ensure all tests pass, ask the user if questions arise.

- [x] 18. Effect attribution
  - [x] 18.1 Implement AttributionWorker
    - On Operation `effective`, open a measurement window (default 7 days); compute per-metric `observed_change`, `estimated_incremental_impact` (null without reliable baseline), `attribution_confidence`; record method + version in `effect_attributions`; lower confidence for overlapping windows
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6_
  - [x] 18.2 Write property test for observed change and honest incremental impact
    - **Property 37: Observed change and honest incremental impact**
    - **Validates: Requirements 8.2**
  - [x] 18.3 Write property test for overlapping-window confidence reduction
    - **Property 38: Overlapping attribution lowers confidence**
    - **Validates: Requirements 8.5**

- [x] 19. Rollback support
  - [x] 19.1 Implement RollbackService
    - Create a compensating Operation (before/after swapped, `OperationSource.MANUAL`, `parentOperationId` = original) for an effective reversible Operation and route through the standard pipeline; warn + require confirmation on overlapping subsequent operations
    - _Requirements: 10.1, 10.2, 10.4, 10.5, 10.6_
  - [x] 19.2 Write property test for compensating-operation value swap
    - **Property 36: Compensating operation swaps values**
    - **Validates: Requirements 10.2**

- [x] 20. Feishu notification integration
  - [x] 20.1 Implement HostingNotificationService and digest worker
    - Typed notifications (approval-needed, effective-confirmed, failed, emergency, data-gap) resolved via `FeishuService`; batch non-urgent into a digest (default 30 min); send emergencies immediately; retry delivery 3× with backoff into `notification_delivery_log`; queue without blocking when Feishu unreachable
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 30.4_
  - [x] 20.2 Write property test for digest batching
    - **Property 48: Notification digest batching**
    - **Validates: Requirements 9.5, 30.4**

- [x] 21. Phase configuration and personality policy scoping
  - [x] 21.1 Implement phase configuration and downgrade cleanup
    - `HostingPhase` config with adjacent-only transitions (V1↔V2↔V3); on downgrade cancel in-scope `awaiting_approval`, supersede/cancel in-scope `pending` and close Outbox rows, stop generating disabled capabilities; leave submitted ops to platform cancel/reconciliation
    - _Requirements: 17.1, 17.2, 17.3, 17.4, 17.5, 17.6_
  - [x] 21.2 Write property test for phase transition validity and downgrade cleanup
    - **Property 42: Phase transition validity and downgrade cleanup**
    - **Validates: Requirements 17.5, 17.6**
  - [x] 21.3 Write property test for engine phase gating
    - **Property 24: Engine phase gating**
    - **Validates: Requirements 4.8, 5.12, 17.2, 17.3, 17.4**
  - [x] 21.4 Implement personality policy scoping
    - Key policies by `(scope, scope_id, personality, rule_version)` with at most one active version per `(scope, scope_id, personality)`; resolve most-specific active scope (campaign → goal → store → organization → system); audit changes
    - _Requirements: 38.1, 38.2, 38.3, 38.4, 38.5, 38.6_
  - [x] 21.5 Write property test for single-active and most-specific resolution
    - **Property 43: Personality policy single-active and most-specific resolution**
    - **Validates: Requirements 38.2, 38.3**

- [x] 22. Production governance (Phase 11)
  - [x] 22.1 Implement kill switch and shadow mode enforcement
    - `KillSwitchService` (system/org/store/campaign): activation cancels `awaiting_approval`, supersedes/cancels in-scope `pending` and closes Outbox rows, closes linked open `approval_requests`, routes submitted ops to cancel/reconciliation; Outbox/engines re-check immediately before submit; `ShadowModeService`
    - _Requirements: 35.1, 35.3, 35.6, 35.7, 40.6_
  - [x] 22.1a Implement persistent canary rollout controls
    - `CanaryRolloutService` persists org rollout state and store membership, exposes authorized governance APIs, and the hosting settings UI controls the real backend state.
    - _Requirements: 35.2, 35.8_
  - [x] 22.1b Implement real SLO and drift monitoring
    - `SloMonitor` and `DriftMonitor` evaluate DB-backed freshness, success-rate, latency, baseline comparisons, and send Feishu-backed emergency notifications through `HostingNotificationService` on breaches.
    - _Requirements: 35.4, 35.5_
  - [x] 22.2 Write property test for kill-switch enforcement
    - **Property 45: Kill switch stops generation and submission**
    - **Validates: Requirements 35.3, 40.6**

- [x] 23. Checkpoint - engines, attribution, governance
  - Ensure all tests pass, ask the user if questions arise.

- [x] 24. Hosting APIs (controllers under /api/advertising/hosting)
  - [x] 24.1 Implement hosting config and brand-word APIs
    - `GET/PUT /hosting/config/{storeId}` (+ goal/campaign overrides) persisting to `hosting_configs` with backend validation (valid personality/execution-mode enums, thresholds in [0,1], only-tighten boundaries); brand-word CRUD; permissions `advertising:manage`/`advertising:execute`
    - _Requirements: 21.1, 21.2, 21.3, 21.4, 21.5, 21.6, 12.1, 12.2, 12.3, 12.4, 12.5, 12.6, 22.1, 22.2, 22.5, 22.6_
  - [x] 24.2 Write property test for settings round-trip and validation
    - **Property 41: Settings save/load round-trip and validation**
    - **Validates: Requirements 12.2, 12.6, 21.3, 21.4**
  - [x] 24.3 Implement cross-organization isolation guard
    - Resolve every inbound id to a store in the caller's org BEFORE the existing `DataScopeService` checks; return 403/404 with no cross-org data; apply in addition to data-scope
    - _Requirements: 39.1, 39.2, 39.3, 39.4, 39.5_
  - [x] 24.4 Write property test for cross-organization isolation
    - **Property 44: Cross-organization isolation**
    - **Validates: Requirements 39.1, 39.3, 39.4, 39.5**
  - [x] 24.5 Write cross-organization negative integration suite
    - Verify org A cannot read/config/approve/trigger/rollback org B resources across every hosting endpoint
    - _Requirements: 39.4_
  - [x] 24.6 Implement optimization trigger and run-detail APIs
    - `POST /hosting/optimize/trigger` returns 202 + `run_id`; enforce minimum manual interval per store; `GET /hosting/optimization-runs/{runId}` returns status/per-campaign results; structured `HOSTING_*` error codes with `request_id`
    - _Requirements: 26.1, 26.2, 26.3, 26.4, 26.5, 28.1, 28.2, 28.3, 28.4, 28.5, 28.6, 28.7_
  - [x] 24.7 Write property test for synchronous-only trigger response
    - **Property 46: Trigger response is synchronous-only**
    - **Validates: Requirements 26.4, 26.5, 28.1**
  - [x] 24.8 Write property test for manual trigger minimum interval
    - **Property 47: Manual trigger minimum interval**
    - **Validates: Requirements 28.4**
  - [x] 24.9 Implement dashboard summary, decisions, and analytics APIs
    - `GET /hosting/dashboard/summary` (today in marketplace tz, indexed + optional Redis cache TTL 60s); `GET /hosting/decisions` and `/{id}`; `GET /hosting/analytics`; `GET /hosting/health`; estimated savings from `effect_attributions`; analytics rates over `ai_decisions` joined to operations
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 27.1, 27.2, 27.3, 27.4, 27.5, 29.1, 29.2, 29.3, 29.4, 29.5, 30.5_
  - [x] 24.10 Write property test for estimated-savings selection
    - **Property 39: Estimated savings selection**
    - **Validates: Requirements 27.3, 29.3**
  - [x] 24.11 Write property test for analytics rate consistency
    - **Property 40: Analytics rates are consistent**
    - **Validates: Requirements 29.2, 29.4, 37.4**
  - [x] 24.12 Wire approve/reject/rollback endpoints
    - `POST /hosting/operations/{id}/approve|reject|rollback` and phase-change admin endpoint, integrated with the existing approval workflow and audit trail
    - _Requirements: 24.1, 24.2, 24.3, 24.4, 24.5, 10.6, 17.5_

- [x] 25. Frontend (React + TypeScript)
  - [x] 25.1 Add hosting API client functions
    - Extend `frontend/src/app/lib/api.ts` with config, dashboard, decisions, analytics, trigger, approve/reject/rollback, brand-word functions following the existing `request`/`requestList` pattern
    - _Requirements: 11.4, 12.1, 24.1_
  - [x] 25.2 Wire the hosting dashboard to real data
    - Update `HostingOverview`/`AiHostingTab` to show managed campaigns, today's decisions, awaiting approval, effective/failed operations, estimated savings, and learning-period status; auto-refresh every 60s; respect data-scope
    - _Requirements: 11.1, 11.2, 11.3, 11.5, 11.6, 11.7, 19.5, 27.1, 27.2_
  - [x] 25.3 Persist hosting settings
    - Wire `HostingSettingsDrawer` to GET/PUT config with backend validation surfacing
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6_
  - [x] 25.4 Implement decision explanation cards
    - Render the immutable Decision_Snapshot reasoning, data, boundaries, predicted impact, and attribution results once the measurement window completes
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5, 13.6, 8.6_
  - [x] 25.5 Implement approve/reject/rollback UI
    - Approve/reject for `awaiting_approval` (gated on `advertising:approve`) and rollback button on reversible effective operations; update list in real time
    - _Requirements: 24.1, 24.2, 24.3, 24.4, 24.5, 10.6_
  - [x] 25.6 Write frontend tests for savings/estimate selection
    - fast-check property/example tests for the estimated-savings display logic
    - _Requirements: 27.3_

- [x] 26. Final checkpoint - full system integration
  - Ensure all tests pass, ask the user if questions arise.

- [x] 27. Unified operations workspace and UI/backend contract alignment
  - [x] 27.1 Merge advertising and product execution into one operator workflow
    - Left navigation groups ad monitoring, campaigns, products, AI content, channel publishing, and operational tasks under 运营中心; advanced Amazon tools remain under 运营工具.
    - Platform scoping keeps Amazon Ads, Google Ads, and TikTok capabilities distinct while the same operations role owns execution.
  - [x] 27.2 Replace character/emoji icons with Lucide SVG icons
    - Platform connections, store/marketplace badges, ratings, and selection states use the shared SVG icon system.
  - [x] 27.3 Align operations permissions with backend responsibilities
    - operations_manager, operation_specialist, and legacy advertising_specialist receive the advertising, product, keyword, import, report, and automation permissions needed by the unified workspace.
  - [x] 27.4 Verify task-to-endpoint mapping
    - Ad monitoring: platform connection APIs plus Amazon hosting/dashboard APIs.
    - Product content: /api/listing-ai/products/* and draft approval APIs.
    - Channel publishing: /api/product-upload/jobs/*; unsupported direct connectors remain explicit manual_export.
    - Channel synchronization: /api/stores/{storeId}/sync, /api/api-sync/jobs, and /api/api-sync/logs.
    - Reports: /api/reports/generate reads real order, advertising, and catalog tables.
  - [x] 27.5 Make the operations command center store- and channel-aware
    - The workbench reads products and channel publishing jobs for the selected store, and renders both in the same operator queue as advertising and sync work.
    - Amazon-only hosting APIs run only for Amazon stores; Shopify/WooCommerce use Google Ads connection state and TikTok uses TikTok Ads connection state.
    - GET /api/stores/{storeId}/sync-jobs accepts status filtering so failed-job pagination is scoped in the backend before the UI receives it.
  - [x] 27.6 Unify the full operations action queue
    - 今日待办 now combines real operation tasks, platform connection issues, store-scoped sync failures, product readiness, channel publishing jobs, Amazon recommendations, AI notifications, and hosting approvals.
    - Only persisted operation tasks expose direct complete/dismiss mutations; all derived work items route to their real handling page.
    - API read failures remain “data unavailable” and are not converted into false “missing connection” or “no products” alerts.
  - [x] 27.7 Align warehouse, inventory, and logistics contracts
    - Warehouse locations are organization-scoped, no longer depend on the active store, and the frontend maps backend location fields to the warehouse table contract.
    - Inventory and movement reads/writes verify the warehouse belongs to the authenticated organization; warehouse and logistics reads require `warehouse:view`, while mutations require `warehouse:manage`.
    - Shipment and exception lists are filtered by the selected accessible store; Amazon shipments expose FBA details, while Shopify/WooCommerce/TikTok shipments use generic logistics terminology and do not call FBA-only endpoints.
    - Navigation groups inventory health, replenishment, warehouses, and logistics shipments under one operations section, with matching route permissions.
  - [x] 27.8 Close the inventory forecast and replenishment data loop
    - Channel inventory sync projects matched SKUs into the core product inventory and one idempotent daily inventory snapshot; unmatched catalog products are logged and never fabricated.
    - Inventory health and replenishment refresh daily forecasts from the latest snapshot and the previous 30 days of non-cancelled core orders, then generate plans only from each product's latest medium/high risk forecast.
    - Inventory reads and writes require an explicit accessible store and matching warehouse permissions; the hard-coded fallback store and misleading snapshot response were removed.
    - Frontend inventory and replenishment contracts normalize backend field/status names, surface action failures, and gate mutations with `warehouse:manage`.
    - Fresh schema and the `20260620_inventory_replenishment_alignment.sql` patch enforce one snapshot/forecast per store, product, and day and align operations-role permissions.
  - [x] 27.9 Harden store and advertising connection boundaries
    - Store, user-store, platform connection, sync job, and sync log APIs now enforce explicit permissions plus organization/store assignment scope; no platform connection is synthesized against a demo store.
    - The Amazon campaign workspace mounts campaign/hosting APIs only for an Amazon store with a tested `connected` Amazon Ads connection, and renders real account, currency, timezone, and sync metadata.
    - Amazon Ads report ingestion uses the asynchronous report create/poll/download lifecycle instead of the nonexistent direct report-row endpoint.
    - Fresh schema and `20260621_store_advertising_security.sql` add store permissions and operations-role grants.
  - [x] 27.10 Bind Amazon Ads OAuth and profit reads to real stores
    - Amazon Ads OAuth requires an explicit accessible Amazon store for authorize, callback, and bind; refresh tokens cannot fall back across stores, and all OAuth endpoints require `import:manage`.
    - The connection wizard requires one real store and one advertising profile, matching the one-connection-per-store/platform data model; client-controlled debug scopes were removed.
    - Profit dashboard/product/attribution endpoints require an explicit accessible store and `finance:view`; the demo-store fallback was removed from backend and frontend contracts.
    - Google Ads currently verifies OAuth only and TikTok Ads authorization/data ingestion remains unsupported; the UI must continue to disclose these limits rather than presenting simulated monitoring.
## Notes

- Tasks marked with `*` are optional test sub-tasks (unit, property, integration) and can be skipped for a faster MVP; core implementation tasks are never optional.
- Each correctness property (1–48) is implemented by a single property-based test (jqwik on the backend, fast-check on the frontend), runs a minimum of 100 iterations, and is tagged `Feature: amazon-ads-ai-hosting-system, Property {number}: {property_text}`.
- Infrastructure-bound concerns (async reporting lifecycle, scheduler cadence, Redis/DB failover, the 500ms summary budget, bean/schema shape, UI layout) use example/integration/smoke tests rather than property tests.
- Each task references specific requirement clauses for traceability; checkpoints ensure incremental validation.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.3", "1.5"] },
    { "id": 1, "tasks": ["1.2", "1.4"] },
    { "id": 2, "tasks": ["1.6", "2.1", "2.2", "5.1", "6.1", "8.1", "21.4"] },
    { "id": 3, "tasks": ["2.3", "2.4", "5.2", "6.2", "8.2", "8.3", "8.5", "10.1", "10.3", "11.1", "21.5"] },
    { "id": 4, "tasks": ["2.5", "2.6", "2.7", "2.8", "2.9", "5.3", "5.4", "5.5", "5.6", "7.1", "8.4", "8.6", "8.7", "10.2", "10.4", "11.2"] },
    { "id": 5, "tasks": ["2.10", "3.1", "3.3", "5.7", "7.2", "7.3", "10.5", "12.1"] },
    { "id": 6, "tasks": ["3.2", "3.4", "5.8", "5.9", "7.4", "10.6", "10.7", "12.2", "12.3", "12.6"] },
    { "id": 7, "tasks": ["10.8", "12.4", "12.5", "14.1", "14.3", "15.1", "15.3", "16.1", "16.2", "16.5"] },
    { "id": 8, "tasks": ["14.2", "14.4", "15.2", "15.4", "16.3", "16.4", "16.6", "18.1", "19.1", "20.1", "21.1", "22.1"] },
    { "id": 9, "tasks": ["18.2", "18.3", "19.2", "20.2", "21.2", "21.3", "22.2", "24.1", "24.3", "24.6", "24.9", "24.12"] },
    { "id": 10, "tasks": ["24.2", "24.4", "24.5", "24.7", "24.8", "24.10", "24.11", "25.1"] },
    { "id": 11, "tasks": ["25.2", "25.3", "25.4", "25.5"] },
    { "id": 12, "tasks": ["25.6"] }
  ]
}
```
