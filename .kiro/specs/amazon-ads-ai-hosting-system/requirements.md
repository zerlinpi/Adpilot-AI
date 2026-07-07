# Requirements Document

## Introduction

This feature delivers the **Amazon Ads AI Hosting System** — the production-grade closed-loop that connects AdPilot's existing advertising infrastructure (Operation state machine, Outbox, StatusPoller, PersonalityResolver, SafetyBoundaryResolver, HostingBidOptimizer V1) to real **Amazon Ads API** execution, and extends it with V2 budget optimization, V3 keyword expansion/negative-keyword engines, real-data dashboard, effect attribution, and Feishu notification integration.

> **Platform terminology note.** This spec targets the **Amazon Ads API** (the advertising API at `advertising-api.amazon.com`, authenticated via Login-with-Amazon), **NOT** the Amazon **SP-API** (Selling Partner API). SP-API serves seller data such as orders, products, and inventory and is integrated separately. All write-back and reporting in this spec uses the Amazon Ads API.

### Existing Capabilities (NOT duplicated here)

The `advertising-workspace-rework` spec (174 tasks, all complete) already delivered:

- **Operation state machine** (`OperationStateMachine`, `SyncState`, `OperationScope`, `OperationSource`). The current machine encodes `awaiting_approval --APPROVE--> submitted`, but the `OutboxWorker` only claims and submits `pending` Operations. This spec therefore requires a corrected approval/auto-execute flow that routes through `pending` so the Outbox actually picks the work up (see Requirements 7 and 24); the state machine MUST be updated to support `awaiting_approval --APPROVE--> pending`.
- **Outbox pattern** (`OutboxWorker` — claims pending operations, submits via connector)
- **StatusPoller** (polls in-flight operations, handles timeout/reconciliation)
- **PlatformWriteConnector interface** (SPI with single-item `submit(ConnectionContext, PlatformChange)`, plus `queryStatus`, `requestCancel`, `mapPlatformStatus` as default methods)
- **WriteCapabilityService** (checks if Store is write-capable)
- **OperationWriteBack** (generic `applyOperation(operationId)` routing)
- **PersonalityResolver** (Campaign override > Goal default > Store default > balanced)
- **PersonalityPolicyService** (resolves configurable numeric policy per personality)
- **SafetyBoundaryResolver** (4-level: Campaign > Goal > Store > System)
- **HostingBidOptimizer / AiHostingOptimizer** (V1 bid optimization — scheduled). Note: the current implementation computes a **campaign-aggregated** ACoS over the lookback window and applies it to every keyword in the campaign; it is not yet truly keyword-level. Requirement 36 (V1 Bid Engine Hardening) defines the work to make it keyword-level and integrate it with the new infrastructure.
- **PendingOverlayService** (joins confirmed + pending values for UI)
- **IdempotencyService** (logicalIdempotencyKey / submissionIdempotencyKey)
- **Callback controller** (inbound platform status callbacks)
- **Approval workflow integration** (awaiting_approval gating)
- **Audit logging** (Operation_Record with full before/after, source, rule_version)
- **external_entity_mappings table** (already in `db/schema.sql`) — the internal↔external entity mapping store reused by this spec.

### What THIS spec adds (net-new)

1. **AmazonAdsWriteConnector** — the concrete `PlatformWriteConnector` implementation for the Amazon Ads API
2. **Amazon Ads Report Sync** — real report ingestion via the Amazon Ads Reporting API asynchronous lifecycle (create → poll → download → ingest)
3. **Amazon Ads Entity Sync** — metadata sync of campaigns/ad groups/keywords (so metrics can be mapped to complete entities)
4. **Data Quality Gate** — freshness/completeness checks before optimization runs
5. **V2 Budget Optimization Engine** — target ACoS-based budget adjustment with spend velocity and inventory awareness
6. **V3 Search Term Harvest & Keyword Engine** — keyword expansion, negative keywords with confidence scoring
7. **Enhanced Safety Boundaries** — additional limits (inventory safety, break-even ACoS, keyword limits, brand-word protection, emergency stop) with per-limit comparison semantics and only-tighten inheritance
8. **Risk Assessment & Approval Routing** — decision-level risk scoring plus an explicit execution-mode gate
9. **Effect Attribution** — post-execution performance measurement distinguishing observed change, estimated incremental impact, and attribution confidence
10. **Feishu Notification Integration** — real-time decision/execution/anomaly notifications resolved via FeishuService
11. **Rollback Support** — compensating operations for reversible changes
12. **Real-Data AI Dashboard** — live hosting dashboard showing managed campaigns, decisions, approvals, outcomes
13. **Settings Persistence** — hosting settings drawer that saves to backend
14. **Decision Explanation Cards** — AI decision cards with full reasoning, data, and an immutable decision input snapshot
15. **Production Rollout & Governance** — shadow mode, canary rollout, kill switches, SLOs, drift monitoring

### Governance Phases

Implementation proceeds in **11** incremental stages; each requirement notes which phase it belongs to. Phase 11 (Production Rollout & Governance) is defined in Requirement 35.

## Glossary

- **Amazon_Ads_API**: The Amazon Advertising API (`advertising-api.amazon.com`, regional hosts for EU/FE), authenticated via a Login-with-Amazon (LWA) access token plus `Amazon-Advertising-API-ClientId` and `Amazon-Advertising-API-Scope` (profile) headers. Distinct from the SP-API.
- **AmazonAdsWriteConnector**: The concrete Spring bean implementing `PlatformWriteConnector` for the `amazon_ads` platform key, calling Amazon Ads API endpoints for keyword bids, campaign budgets, campaign state, keyword creation, and negative keyword creation.
- **Amazon_Ads_Reporting_API**: The Amazon Ads **asynchronous** reporting endpoints. A report is requested (create), its status is polled until completed, a (often GZIP-compressed) download URL is retrieved, downloaded, and ingested. There is no synchronous "report-rows" fetch.
- **Report_Sync_Job**: A scheduled job that runs the full asynchronous report lifecycle (create → poll status → fetch download URL → download → decompress → validate → idempotent upsert) and ingests Amazon Ads performance data into the local database, mapped by external Amazon entity IDs.
- **Entity_Sync_Job**: A job that syncs Amazon Ads **entity metadata** (campaigns, ad groups, keywords — names, states, hierarchy) into local entities and `external_entity_mappings`, run before/independently of metric ingestion so metrics attach to complete entities.
- **Read_After_Write_Verification**: The Amazon Ads write verification model: after a successful submit, the System stores the Amazon request ID and external entity ID, waits a configurable delay, re-reads the entity's current field value via the Amazon Ads API, and compares it to the Operation's `after_value`. A match advances the Operation to `effective`; a mismatch advances it to `reconciliation_required`. Entity lifecycle state (ENABLED/PAUSED/ARCHIVED) is NOT itself treated as Operation success.
- **Data_Quality_Gate**: A pre-optimization check that verifies data freshness (report age), completeness (minimum days of data), and entity coverage before allowing the optimization engine to run.
- **Data_Status**: A per-row flag on performance data indicating whether the row is `preliminary` (current/recent day, subject to Amazon attribution lag and revision) or `finalized` (settled).
- **V2_Budget_Engine**: The budget optimization engine that adjusts campaign daily budgets based on target ACoS, spend velocity, sales potential, and inventory constraints.
- **V3_Keyword_Engine**: The keyword expansion and negative-keyword engine that harvests search terms, proposes new keywords (exact/phrase/broad), and proposes negative keywords with confidence + evidence scoring.
- **Optimization_Coordinator**: The component that runs after the V1/V2/V3 engines produce candidate decisions against a single immutable data snapshot, then de-conflicts, prioritizes, applies safety-boundary clipping, and only then creates Operations.
- **Decision_Snapshot**: The immutable JSON record persisted on each AI Operation at creation time, capturing the exact inputs and reasoning (see Requirement 13 / 34).
- **Execution_Mode**: The per-store/goal/campaign mode governing whether decisions execute: `observe_only`, `recommend_only`, `approval_required`, or `auto_execute`. New stores default to `observe_only`.
- **Brand_Word_List**: A per-store configurable list of brand terms that the V3 engine protects from negative-keyword addition.
- **Break_Even_ACoS**: The ACoS at which a product's advertising spend equals its gross margin; used as a hard stop boundary.
- **Emergency_Stop**: An immediate halt of all AI operations for a campaign/store triggered when a critical safety threshold is breached (e.g., daily spend exceeds the configured multiplier of budget, ACoS exceeds the configured multiplier of target). Modeled as a boolean OR across boundary levels.
- **Effect_Attribution**: The post-execution measurement that compares key metrics in a defined window before vs. after an operation took effect, distinguishing `observed_change`, `estimated_incremental_impact`, and `attribution_confidence`.
- **Decision_Explanation**: A structured explanation attached to each AI decision containing: the triggering data, the rule/policy that fired, the personality parameters used, the safety boundary applied, and the predicted impact.
- **Risk_Score**: A numeric assessment (0.0–1.0) of a proposed AI decision's risk level, computed deterministically from change magnitude, data confidence, and historical volatility.
- **Approval_Threshold**: The risk score above which a decision requires human approval rather than auto-execution (only consulted when Execution_Mode is `auto_execute`).
- **Hosting_Dashboard**: The real-data UI page showing managed campaigns count, today's decisions, awaiting approval, effective operations, failed operations, and estimated savings.
- **Settings_Drawer**: The UI component that configures and persists hosting-level settings (personality, boundaries, execution mode, auto-execute threshold, notification preferences) per store/goal/campaign.
- **Compensating_Operation**: A reverse operation that undoes a previously effective change (e.g., restoring a bid to its pre-change value), created as a new Operation with `parentOperationId` linking to the original.
- **Shadow_Mode**: A rollout mode in which engines generate and persist decisions and explanations but never submit to Amazon, used to validate behavior against production data.
- **Kill_Switch**: A System/organization/store/campaign-level flag that immediately stops new decision generation and submission for the scoped entities.

## Requirements

### Requirement 1: Amazon Ads Write Connector Implementation

**User Story:** As the system, I want a concrete Amazon Ads write connector registered as a Spring bean, so that platform_mutation Operations can be submitted to real Amazon Ads campaigns.

**Phase:** 2 (Amazon Ads Write Integration)

#### Acceptance Criteria

1. THE AmazonAdsWriteConnector SHALL implement PlatformWriteConnector with platform key `amazon_ads` and register as a Spring bean.
2. WHEN a keyword bid update Operation is submitted, THE AmazonAdsWriteConnector SHALL call the Amazon Ads API sponsored products keyword endpoint with the mapped Amazon keyword ID and new bid value.
3. WHEN a campaign budget update Operation is submitted, THE AmazonAdsWriteConnector SHALL call the Amazon Ads API campaign endpoint with the mapped Amazon campaign ID and new daily budget value.
4. WHEN a campaign state change Operation is submitted (enable/pause), THE AmazonAdsWriteConnector SHALL call the Amazon Ads API campaign endpoint with the mapped Amazon campaign ID and new state value.
5. WHEN a keyword creation Operation is submitted, THE AmazonAdsWriteConnector SHALL call the Amazon Ads API keyword creation endpoint with the ad group ID, keyword text, match type, and bid.
6. WHEN a negative keyword creation Operation is submitted, THE AmazonAdsWriteConnector SHALL call the Amazon Ads API negative keyword endpoint with the campaign ID (or ad group ID, per level), keyword text, and match type.
7. THE AmazonAdsWriteConnector SHALL use the Store's PlatformConnection refresh_token to obtain a fresh LWA access token before each API call when the current token is expired or absent.
8. WHEN the Amazon Ads API returns HTTP 429 (rate limit), THE AmazonAdsWriteConnector SHALL return a rejected result flagged `retryable` carrying the `retryAfter` value from the Retry-After header, rather than blocking the calling thread; the Outbox owns the delayed retry (Requirement 30.7) by scheduling `next_attempt_at`.
9. WHEN the Amazon Ads API returns a transient error (HTTP 500, 502, 503), THE AmazonAdsWriteConnector SHALL return a rejected result flagged `retryable` with a suggested backoff; it SHALL NOT sleep/loop internally across attempts. All delayed retry scheduling is performed by the Outbox, so there is a single retry owner (Requirement 30.7).
10. THE AmazonAdsWriteConnector SHALL enforce idempotency using **local** controls — duplicate-submission detection keyed by the Operation's logicalIdempotencyKey and a write-before/write-after read of the entity — rather than relying on a custom Amazon idempotency request header (the Amazon Ads API does not guarantee one). The submissionIdempotencyKey SHALL be retained in the internal Operation_Record for audit and correlation.
11. WHEN the Amazon Ads API returns a success response, THE AmazonAdsWriteConnector SHALL return an accepted PlatformWriteResult that carries the Amazon **request ID** and the affected **external entity ID** as structured fields (see Requirement 16.5 — these SHALL NOT be packed into the free-text message string); acceptance means "the write request was accepted", NOT "the change is confirmed effective".
12. WHEN the Amazon Ads API returns a rejection (HTTP 400, 422), THE AmazonAdsWriteConnector SHALL return a rejected PlatformWriteResult carrying the Amazon **error code** as a structured field plus the message as the failure reason.
13. THE write-verification SHALL be performed via an extended SPI capable of value comparison (the current `queryStatus(ConnectionContext, String platformReference)` cannot, since it has no entity type, field, or expected value): per Requirement 16.6 the System SHALL add either `verify(ConnectionContext, PlatformChange, SubmissionMetadata)` OR a method where the StatusPoller loads the Operation and the connector returns the structured live field value(s) for the business layer to compare against the Operation's `after_value`.
14. THE write-verification result SHALL map a verified value match to `effective`, a value mismatch to `reconciliation_required`, and a read failure to a retry/`reconciliation_required` outcome; entity lifecycle state strings (ENABLED, PAUSED, ARCHIVED, PENDING) SHALL NOT be mapped directly to `effective` unless they are the actual field the Operation changed.
15. IF the Store's PlatformConnection refresh_token is invalid or revoked, THEN THE AmazonAdsWriteConnector SHALL return a rejected PlatformWriteResult with reason "TOKEN_INVALID" and the Store's write capability SHALL be marked as degraded.

### Requirement 2: Amazon Ads Report Sync

**User Story:** As an operator, I want real Amazon Ads performance data synced into AdPilot through the asynchronous reporting lifecycle, so that optimization and monitoring decisions are based on actual campaign metrics rather than demo data.

**Phase:** 1 (Foundation)

#### Acceptance Criteria

1. THE Report_Sync_Job SHALL run on a configurable schedule for each store with an active Amazon Ads PlatformConnection, supporting three sync cadences: (a) an intra-day incremental sync at a configurable interval (default hourly) for the current day's preliminary data; (b) a daily sync (default 06:00 in the store's marketplace timezone) that ingests recent days as preliminary and promotes rows older than `finalizationLagDays` to finalized; and (c) a rolling backfill of the last 7–14 days to absorb Amazon attribution lag.
2. WHEN the Report_Sync_Job executes, THE System SHALL drive the full Amazon Ads Reporting API asynchronous lifecycle for each report type (Sponsored Products campaign, keyword, and search term): create the report request, poll report status until COMPLETED (or FAILED/EXPIRED), retrieve the download URL, download and decompress the (GZIP) payload, and validate the returned reportId, requested date range, and row count before ingestion.
3. THE Report_Sync_Job SHALL resolve Amazon external entity IDs (campaign_id, ad_group_id, keyword_id) to internal AdPilot UUIDs using the `external_entity_mappings` table; rows whose entity cannot be resolved SHALL be routed to a quarantine store (see Requirement 14.6) rather than creating incomplete entities.
4. THE Report_Sync_Job SHALL idempotently upsert performance rows keyed by (store_id, entity_type, entity_id, report_date), persisting metrics (impressions, clicks, spend, sales, orders, acos, ctr, cvr, cpc), the row `currency`, a `data_status`, a `data_version`, and an `updated_at` timestamp. Data_Status SHALL be derived from age, not assumed: rows for the current day (D0) and recent days SHALL be `preliminary`; a row SHALL only become `finalized` after a configurable `finalizationLagDays` (covering Amazon's 7–14 day attribution backfill) has elapsed past its report_date. WHEN a backfill changes a previously ingested row, THE System SHALL increment its `data_version`. Gap detection (Requirement 2.8) SHALL be evaluated relative to the `expectedFinalizedDate` (today − finalizationLagDays), not relative to today.
5. WHEN a report row corresponds to an entity not yet present locally, THE Report_Sync_Job SHALL NOT create a partial entity from metric data alone; instead it SHALL defer to the Entity_Sync_Job (Requirement 14) and quarantine the metric row until the entity exists.
6. WHEN the Amazon Ads Reporting API returns an error, times out, or the report status resolves to FAILED/EXPIRED, THE Report_Sync_Job SHALL retry up to 3 times with exponential backoff, request report re-generation where applicable, and record the failure linked to the specific sync run (sync_record_errors SHALL reference the concrete report sync run/job, not a generic job).
7. THE System SHALL persist each report sync execution in a dedicated `report_sync_runs` table (run_id, store_id, report_type, requested_date_range, report_status, data_status, row_count, started_at, completed_at, error) rather than overloading the generic `sync_watermarks` table (which only carries store_id/entity_type/watermark_at/cursor_token); `report_sync_runs` records the report_date, report type, status, and row count so subsequent runs can detect gaps and distinguish preliminary from finalized coverage.
8. IF the Report_Sync_Job detects a gap of more than 2 days in the finalized coverage recorded in `report_sync_runs` (Requirement 2.7) relative to the `expectedFinalizedDate` (= today − `finalizationLagDays`), THEN THE System SHALL log a data-gap warning and send a Feishu notification (resolved via FeishuService per Requirement 9). Finalized coverage SHALL be determined from `report_sync_runs`; the generic `sync_watermarks` table SHALL be used only for incremental cursors, not for report coverage/completeness judgments.

### Requirement 3: Data Quality Gate

**User Story:** As the system, I want to verify data freshness and completeness before running optimization, so that AI decisions are never based on stale or incomplete metrics.

**Phase:** 3 (Data Quality)

#### Acceptance Criteria

1. WHEN the optimization engine is triggered for a campaign, THE Data_Quality_Gate SHALL verify that performance data for that campaign exists within the configured freshness window (default: data no older than 48 hours).
2. WHEN the optimization engine is triggered for a campaign, THE Data_Quality_Gate SHALL verify completeness against the **report coverage interval recorded in `report_sync_runs`** rather than requiring contiguous performance rows: a date with zero impressions may legitimately have no row, so completeness SHALL be judged by whether the required lookback range is covered by finalized report runs (Requirement 2.7), not by counting non-empty rows. Rules that demand settled data SHALL require `finalized` coverage.
3. IF the Data_Quality_Gate freshness check fails, THEN THE System SHALL skip optimization for that campaign and record the skip reason as "DATA_STALE" in the optimization run log.
4. IF the Data_Quality_Gate completeness check fails, THEN THE System SHALL skip optimization for that campaign and record the skip reason as "DATA_INCOMPLETE" in the optimization run log.
5. THE Data_Quality_Gate SHALL operate fail-closed: when data freshness or completeness cannot be determined (database error, missing watermarks), THE System SHALL refuse to optimize rather than proceeding with uncertain data.
6. WHEN the Data_Quality_Gate rejects a campaign, THE System SHALL include the campaign in the next Feishu notification batch with the specific rejection reason.
7. THE Data_Quality_Gate SHALL be re-run immediately before an Operation is submitted (not only at decision-creation time), so that decisions which became stale while queued are not submitted on outdated data.

### Requirement 4: V2 Budget Optimization Engine

**User Story:** As an operator, I want the AI to automatically adjust campaign daily budgets based on ACoS performance, spend velocity, and inventory levels, so that budget is allocated efficiently without manual intervention.

**Phase:** 6 (V2 Budget Engine)

#### Acceptance Criteria

1. THE V2_Budget_Engine SHALL compute a candidate daily budget for each hosted campaign from a single immutable data snapshot based on: target ACoS from the campaign's goal, actual ACoS over the personality policy's lookbackDays, current daily spend velocity, and available inventory days. It SHALL emit candidate decisions to the Optimization_Coordinator (Requirement 23) rather than creating Operations directly.
2. WHEN actual ACoS is below target ACoS AND inventory days exceed the safety boundary's `inventoryHealthyDays`, THE V2_Budget_Engine SHALL propose a budget increase capped at the personality policy's maxDailyBudgetIncreaseRatio.
3. WHEN actual ACoS exceeds target ACoS by more than the personality policy's acosToleranceRatio, THE V2_Budget_Engine SHALL propose a budget decrease bounded by the safety boundary's maxDailyBudgetDecreaseRatio.
4. WHEN inventory days are below the safety boundary's inventorySafetyDays, THE V2_Budget_Engine SHALL propose a budget decrease regardless of ACoS performance, applying the inventory_safety boundary. The 14/7/3-day thresholds referenced elsewhere are configurable boundary values, not hardcoded constants.
5. THE V2_Budget_Engine SHALL clamp all proposed budget changes within the resolved Safety_Boundary's `maxDailyBudget`, `minDailyBudget`, `maxDailyBudgetIncreaseRatio`, and `maxDailyBudgetDecreaseRatio`.
6. THE V2_Budget_Engine SHALL respect the personality policy's adjustmentCooldownHours: a campaign whose last budget adjustment is within the cooldown window SHALL be skipped with reason "COOLDOWN".
7. WHEN the V2_Budget_Engine's candidate is accepted by the Optimization_Coordinator, THE System SHALL create a platform_mutation Operation with Operation_Source `ai_hosting`, recording the before value, after value, personality used, rule_version, the Decision_Snapshot (Requirement 34), and a structured Decision_Explanation.
8. THE V2_Budget_Engine SHALL only run when the AiHostingOptimizer's activePhase returns V2 or higher.
9. IF a campaign has no target ACoS defined (goal has no target_acos), THEN THE V2_Budget_Engine SHALL skip that campaign with reason "NO_TARGET_ACOS".

### Requirement 5: V3 Search Term Harvest and Keyword Engine

**User Story:** As an operator, I want the AI to automatically harvest high-performing search terms into keywords and suppress wasteful terms as negatives, so that campaign targeting improves continuously.

**Phase:** 8 (V3 Keyword Engine)

#### Acceptance Criteria

1. THE V3_Keyword_Engine SHALL analyze search term performance data (from the search-term daily store, Requirement 31) over the personality policy's lookbackDays and score each term's confidence based on clicks, orders, ACoS, and statistical significance.
2. WHEN a search term has produced orders AND its ACoS is below target ACoS AND confidence exceeds the personality policy's keywordConfidenceThreshold, THE V3_Keyword_Engine SHALL propose adding it as an exact-match keyword.
3. WHEN a search term has high clicks (above personality policy minClicks) AND zero orders over the lookback period AND confidence exceeds threshold, THE V3_Keyword_Engine SHALL propose adding it as a negative keyword.
4. THE V3_Keyword_Engine SHALL check every proposed negative keyword against the Store's Brand_Word_List: IF the term matches a brand word, THEN THE System SHALL reject the negative proposal and log the rejection reason as "BRAND_PROTECTED".
5. THE V3_Keyword_Engine SHALL respect the personality policy's maxKeywordsAddedPerDay limit (counted across all runs in the marketplace day): once the daily limit is reached for a campaign, remaining proposals are queued for the next day.
6. THE V3_Keyword_Engine SHALL respect the personality policy's maxNegativesAddedPerDay limit (counted across all runs in the marketplace day): once the daily limit is reached for a campaign, remaining proposals are queued for the next day.
7. WHEN the personality policy's keywordExpansionMode is `off`, THE V3_Keyword_Engine SHALL not propose any keyword additions for campaigns under that personality.
8. WHEN the personality policy's negativeKeywordMode is `suggest`, THE V3_Keyword_Engine SHALL mark its negative-keyword candidates as approval-requiring; this interacts with Execution_Mode (Requirement 7.3) as follows: under `observe_only`/`recommend_only` no Operation is created (the candidate is persisted in `ai_decisions` only), and under `approval_required`/`auto_execute` the candidate is created as an `awaiting_approval` Operation regardless of Risk_Score. `suggest` SHALL never auto-execute.
9. WHEN the personality policy's negativeKeywordMode is `auto`, THE V3_Keyword_Engine MAY auto-execute negative-keyword additions only when Execution_Mode is `auto_execute` AND the store explicitly opts in; otherwise (including under `observe_only`/`recommend_only`/`approval_required`) the candidate follows the Execution_Mode rules of Requirement 7.3. Negative-keyword additions remain high-risk per Requirement 7.5.
10. THE V3_Keyword_Engine SHALL support match types: exact, phrase, and broad for keyword additions, with the default determined by the search term's performance pattern.
11. WHEN the V3_Keyword_Engine's candidate is accepted, THE System SHALL create a platform_mutation Operation recording confidence score, evidence (clicks, orders, ACoS, days of data), the Decision_Snapshot, and the Decision_Explanation. Keyword and negative-keyword additions SHALL be marked non-reversible (Requirement 10).
12. THE V3_Keyword_Engine SHALL only run when the AiHostingOptimizer's activePhase returns V3.

### Requirement 6: Enhanced Safety Boundaries

**User Story:** As an operator, I want comprehensive safety boundaries with well-defined comparison semantics that can only be tightened (never loosened) as they inherit, so that I am protected against excessive AI actions at every level.

**Phase:** 4 (Safety Enhancement)

#### Acceptance Criteria

1. THE SafetyBoundaryResolver SHALL support additional limits beyond the existing four (minBid, maxBid, maxCpc, maxDailyBudget): maxBidAdjustmentRatio, maxDailyBudgetIncreaseRatio, maxDailyBudgetDecreaseRatio, minDailyBudget, maxOperationsPerRun, maxOperationsPerDay, minimumDataDays, learningPeriodDays, cooldownHours, inventorySafetyDays, inventoryHealthyDays, inventoryCriticalDays, breakEvenAcos, emergencySpendToBudgetMultiplier, emergencyAcosToTargetMultiplier, maxKeywordsPerDay, maxNegativesPerDay, and emergencyStop. The emergency multipliers and inventory healthy/critical-day thresholds referenced in Requirements 4, 6.5, and 25 SHALL be these configurable boundary values, not hardcoded constants.
2. THE SafetyBoundaryResolver SHALL define explicit `comparison_semantics` per limit governing what "more restrictive" means:
   - **Upper-bound limits** (maxBid, maxCpc, maxDailyBudget, maxBidAdjustmentRatio, maxDailyBudgetIncreaseRatio, maxDailyBudgetDecreaseRatio, maxOperationsPerRun, maxOperationsPerDay, maxKeywordsPerDay, maxNegativesPerDay, breakEvenAcos, emergencySpendToBudgetMultiplier, emergencyAcosToTargetMultiplier): the **smaller** value is more restrictive.
   - **Lower-bound limits** (minBid, minDailyBudget, minimumDataDays, learningPeriodDays, cooldownHours, inventorySafetyDays, inventoryHealthyDays, inventoryCriticalDays): the **larger** value is more restrictive.
   - **emergencyStop**: boolean **OR** — if any level enables it, it is enabled.
   - **allowed-action sets**: resolved as **set intersection** — an action is permitted only if permitted at every level.
3. WHEN a user attempts to configure a boundary at a lower level (e.g., campaign) that is LESS restrictive than the boundary at a higher level (per that limit's comparison_semantics), THE System SHALL reject the configuration and return an error explaining which higher-level boundary constrains it.
4. THE System SHALL support a 5-level boundary hierarchy. The resolver SHALL evaluate levels in the order **campaign → goal → store → organization → system** and produce the final effective value as the intersection (most-restrictive-wins) of all level values for each limit, so inheritance can only tighten.
5. WHEN an emergency_stop boundary is breached (daily spend exceeds `emergencySpendToBudgetMultiplier` × daily budget, OR ACoS exceeds `emergencyAcosToTargetMultiplier` × target ACoS), THE System SHALL distinguish two effects: (a) the **internal AI Kill Switch** for that campaign takes effect **immediately and automatically** (no approval) — halting new AI decision generation/submission for the campaign and creating an alert; and (b) any **Amazon-facing action** (e.g., pausing the Amazon campaign, or a budget reduction) follows its configured Execution_Mode — auto-executed or routed to approval per policy. The internal halt SHALL NOT wait on approval.
6. IF performance data for a campaign is older than the configured data staleness threshold, THEN THE System SHALL treat the boundary as fail-closed and refuse all AI operations for that campaign until fresh data arrives.
7. THE System SHALL persist boundary configurations at each level in a `safety_boundaries` table with columns: level, level_id, limit_name, limit_value, value_type (amount/ratio/integer/boolean), unit, currency, comparison_semantics, updated_by, updated_at — so a single `limit_value` column does not ambiguously carry amounts, ratios, integers, and booleans.
8. WHEN a boundary is updated, THE System SHALL validate cross-field constraints (e.g., minBid ≤ maxBid, minDailyBudget ≤ maxDailyBudget, inventoryCriticalDays ≤ inventorySafetyDays ≤ inventoryHealthyDays) and record the change in the audit log with before/after values and the acting user.

### Requirement 7: Risk Assessment, Execution Mode, and Approval Routing

**User Story:** As an operator, I want AI decisions gated by an explicit execution mode and risk score, so that risky changes always require approval and low-risk changes only auto-execute when I have enabled it.

**Phase:** 5 (Risk & Approval)

#### Acceptance Criteria

1. WHEN the optimization engine produces a decision, THE System SHALL compute a Risk_Score (0.0–1.0) deterministically based on: change magnitude relative to current value, data confidence (days of data, statistical significance), historical volatility of the metric, and absolute dollar impact.
2. THE System SHALL resolve an Execution_Mode for each decision (campaign > goal > store inheritance) with values `observe_only`, `recommend_only`, `approval_required`, or `auto_execute`. New stores SHALL default to `observe_only`.
3. THE System SHALL apply Execution_Mode to decision persistence and Operation creation as follows:
   - `observe_only`: persist the decision in `ai_decisions` only; create no Operation.
   - `recommend_only`: persist the decision in `ai_decisions` and surface it in the dashboard; create no Operation and never auto-submit.
   - `approval_required`: immediately create an `awaiting_approval` Operation linked to the `ai_decisions` row; approval later flips that same Operation to `pending` (no new Operation is created at approval time).
   - `auto_execute`: create a `pending` Operation (with Outbox row) directly, subject to the high-risk gate (7.4/7.5).
4. WHEN Execution_Mode is `auto_execute`: IF the decision is high-risk (7.5) OR the Risk_Score is at/above the store's configured auto-execute threshold (default: 0.3), THEN THE System SHALL create the Operation in `awaiting_approval` and atomically create the linked `approval_requests` record (Requirement 40); OTHERWISE (not high-risk AND below threshold) THE System SHALL create the Operation in `pending` with its Outbox row in the same transaction and let the `OutboxWorker` perform the `pending → submitted` SUBMIT. The System SHALL NOT apply the SUBMIT event itself, because the OutboxWorker only claims `pending` Operations.
5. THE high-risk classification and approval gate apply ONLY when Execution_Mode is `approval_required` or `auto_execute` (in `observe_only`/`recommend_only` no Operation is created at all — Requirement 7.3 — so there is nothing to gate). Within those executing modes, the following are **high-risk** and SHALL route to `awaiting_approval` irrespective of Risk_Score (except the Requirement 5.9 opt-in): Amazon campaign state changes (pause/enable), keyword/negative-keyword additions, and **large** budget reductions exceeding a configurable decrease threshold (e.g., greater than `maxDailyBudgetDecreaseRatio`). **Ordinary** budget reductions within that threshold SHALL be routed by Risk_Score and Execution_Mode like other normal adjustments. An emergency-stop-driven Amazon budget reduction (Requirement 6.5b) SHALL be governed by the dedicated `emergency_auto_action_enabled` setting: when enabled it auto-executes, otherwise it routes to approval. A negative keyword that matches a Brand_Word (Requirements 5.4, 22.4) is NOT an approvable high-risk action — it SHALL be **hard-rejected** and never proposed. The internal AI Kill Switch (Requirement 6.5a) is automatic and not subject to this approval gate.
6. THE System SHALL allow the auto-execute threshold to be configured per store in the Settings_Drawer, with values between 0.0 and 1.0; this threshold is only consulted when Execution_Mode is `auto_execute`.
7. WHEN an Operation is in `awaiting_approval` and an authorized user approves it, THE System SHALL apply the `APPROVE` event to advance the Operation to `pending` (and ensure its Outbox row exists), so that the `OutboxWorker` claims it and performs the `pending → submitted` SUBMIT. This requires updating `OperationStateMachine` so APPROVE maps `awaiting_approval → pending`; the spec deliberately does NOT codify the current `awaiting_approval → submitted` edge, because the Outbox would never pick a `submitted` row up.
8. WHEN an Operation is in `awaiting_approval` and an authorized user rejects it, THE System SHALL apply the `REJECT` event, advancing the Operation to `cancelled` with statusReason recording the rejector and reason.
9. THE Risk_Score computation SHALL be deterministic and SHALL record its formula version in the Decision_Snapshot: given the same inputs and configuration, THE System SHALL produce the same score.
10. THE System SHALL evaluate every decision through a single, fixed precedence pipeline so all modules agree on the outcome: **(1) Kill Switch** (Requirement 35.3 — if active for the scope, no decision is created/executed); then **(2) Shadow Mode** (Requirement 35.1 — generate and persist the decision in `ai_decisions` but never create a submittable Operation); then **(3) Active Phase** (Requirement 17 — engine must be enabled for the current phase); then **(4) Execution_Mode** (Requirement 7.3 — observe_only/recommend_only never create Operations); then **(5) High-Risk Gate** (Requirement 7.5 — force `awaiting_approval`); then **(6) Risk Threshold** (Requirement 7.4 — at/above threshold → `awaiting_approval`, below → `pending`). A higher-precedence stop SHALL short-circuit all lower stages (e.g., when Shadow Mode is on, `auto_execute` SHALL NOT submit).

### Requirement 8: Effect Attribution

**User Story:** As an operator, I want to see the measurable impact of each AI decision after execution, clearly distinguishing observed change from estimated AI contribution, so that I can evaluate the AI honestly.

**Phase:** 10 (Effect Attribution)

#### Acceptance Criteria

1. WHEN an Operation reaches the `effective` SyncState, THE System SHALL record the operation's effect_attribution_start timestamp and begin a measurement window (default: 7 days).
2. WHEN the measurement window completes, THE System SHALL compute, for each metric (ACoS, spend, sales, impressions, clicks), the `observed_change` (raw before/after delta comparing the lookback window before the operation to the measurement window after), an `estimated_incremental_impact` (the portion plausibly attributable to the AI action), and an `attribution_confidence` (0.0–1.0). THE System SHALL record the `attribution_method` and `method_version` used to estimate incremental impact (e.g., naive before/after, seasonally-adjusted, control-group/comparable-campaign baseline). WHEN no reliable baseline/control is available, THE System SHALL set `estimated_incremental_impact` to null and display only the `observed_change`, rather than presenting an unjustified incremental figure.
3. THE System SHALL store effect attribution results in an `effect_attributions` table with columns: operation_id, metric_name, before_value, after_value, observed_change, observed_change_percent, estimated_incremental_impact (nullable), attribution_method, method_version, attribution_confidence, measurement_start, measurement_end.
4. THE Hosting_Dashboard SHALL display aggregated effect attribution metrics (total estimated savings, total sales lift, average ACoS improvement) and SHALL label every such figure as an **estimate**, never presenting the full observed change as proven AI contribution.
5. WHEN multiple operations affect the same campaign within overlapping measurement windows, THE System SHALL record the overlap and lower the attribution_confidence rather than crediting any single operation with the full observed change.
6. THE Decision_Explanation card in the UI SHALL display the effect attribution results (observed change, estimated incremental impact, and confidence) once the measurement window completes for that operation.

### Requirement 9: Feishu Notification Integration

**User Story:** As an operator, I want to receive Feishu notifications when AI decisions are made, executed, or encounter problems, so that I stay informed without constantly checking the dashboard.

**Phase:** 4 (Notifications)

#### Acceptance Criteria

1. WHEN an AI decision is created that requires approval (`awaiting_approval`), THE System SHALL send a Feishu notification containing: campaign name, decision type, proposed change, risk score, and a deep link to the approval page.
2. WHEN an Operation transitions to `effective`, THE System SHALL send a Feishu notification containing: campaign name, change description, and the confirmed (read-after-write verified) platform result.
3. WHEN an Operation transitions to `failed`, THE System SHALL send a Feishu notification containing: campaign name, change description, failure reason, and whether automatic retry will occur.
4. WHEN an Emergency_Stop is triggered, THE System SHALL send a Feishu notification immediately with priority marking, containing: campaign name, triggering metric, threshold breached, and actions taken.
5. THE System SHALL batch non-urgent notifications (effective confirmations) into a configurable digest interval (default: every 30 minutes) to avoid notification fatigue.
6. THE System SHALL resolve the active Feishu integration for the organization/store via `FeishuService`, supporting both the `app` and `webhook` provider types; the webhook URL and any signing secret SHALL be read from the resolved integration (the `feishu_notification_rules` table references the integration and does not itself store the webhook URL), and secrets SHALL be decrypted only at point of use.
7. IF Feishu delivery fails, THEN THE System SHALL retry up to 3 times with exponential backoff and record the failure in a notification_delivery_log.

### Requirement 10: Rollback Support

**User Story:** As an operator, I want to undo an AI decision that has taken effect on Amazon, so that I can quickly reverse changes that are underperforming.

**Phase:** 9 (Rollback)

#### Acceptance Criteria

1. WHEN an Operation is in the `effective` state and its `reversible` flag is true, THE System SHALL allow the user to initiate a rollback through the dashboard.
2. WHEN a rollback is initiated, THE System SHALL create a new Compensating_Operation with the before/after values swapped (the original operation's before_value becomes the new after_value), Operation_Source set to `manual`, and parentOperationId referencing the original operation.
3. THE System SHALL mark keyword bid changes and campaign budget changes as reversible (reversible=true) and keyword/negative keyword additions as non-reversible (reversible=false).
4. WHEN a Compensating_Operation is created, THE System SHALL route it through the standard Operation pipeline: risk assessment, execution-mode/approval routing, OutboxWorker submission, and read-after-write verification.
5. IF a rollback is attempted for an operation whose campaign has had subsequent overlapping operations on the same field, THEN THE System SHALL warn the user that the rollback may conflict with subsequent changes and require explicit confirmation.
6. THE Hosting_Dashboard SHALL display a "Rollback" button on effective operations that are reversible and have not already been rolled back.

### Requirement 11: Real-Data Hosting Dashboard

**User Story:** As an operator, I want a hosting dashboard that shows real campaign data, live decision statuses, and actionable metrics, so that I can monitor and manage AI hosting without relying on demo data.

**Phase:** 7 (Dashboard)

#### Acceptance Criteria

1. THE Hosting_Dashboard SHALL display summary cards showing: total managed (hosted) campaigns count, today's AI decisions count, decisions awaiting approval count, operations effective today count, operations failed today count.
2. THE Hosting_Dashboard SHALL query real data from the operations table and campaigns table filtered by the Active_Store and current date (in marketplace timezone).
3. THE Hosting_Dashboard SHALL display an **estimated** savings metric computed from effect attribution data (sum of estimated_incremental_impact on spend for effective operations where ACoS improved), explicitly labeled as an estimate.
4. THE Hosting_Dashboard SHALL display a decision list showing the most recent AI decisions with: campaign name, decision type, proposed change, risk score, current SyncState, and timestamp.
5. WHEN the user clicks a decision in the list, THE System SHALL navigate to a Decision_Explanation card showing full details: triggering metrics, rule/policy applied, personality parameters, safety boundary check result, and effect attribution (when available).
6. THE Hosting_Dashboard SHALL auto-refresh data every 60 seconds without requiring manual page reload.
7. THE Hosting_Dashboard SHALL respect data-scope permissions: operators see only decisions for campaigns within their assigned stores/data-scopes.

### Requirement 12: Hosting Settings Persistence

**User Story:** As an operator, I want hosting settings (personality, boundaries, execution mode, auto-execute threshold, notifications) to persist when I save them, so that my configuration survives reloads and is applied by the backend.

**Phase:** 5 (Settings)

#### Acceptance Criteria

1. WHEN the user opens the Settings_Drawer for a store, THE System SHALL load the current hosting configuration from the backend: default_personality, execution_mode, auto_execute_threshold, notification_preferences, and safety boundary overrides.
2. WHEN the user saves settings, THE System SHALL persist the configuration via a PUT endpoint and return the saved values as confirmation.
3. THE Settings_Drawer SHALL support configuration at three scopes: store-level, goal-level, and campaign-level.
4. WHEN a setting is saved, THE System SHALL create a local_configuration Operation recording the change with before/after values in the audit trail.
5. THE Settings_Drawer SHALL display which level each setting is inherited from and indicate when a value overrides a higher-level default.
6. THE System SHALL validate setting values on the backend: personality must be a valid Machine_Value_Enum, thresholds must be within 0.0–1.0, execution_mode must be a valid enum value, and boundary values must satisfy the only-tighten constraint per each limit's comparison_semantics.

### Requirement 13: Decision Explanation Cards

**User Story:** As an operator, I want each AI decision to show a complete, immutable explanation of why it was made and what data drove it, so that I can trust and verify the AI's reasoning.

**Phase:** 7 (Explanations)

#### Acceptance Criteria

1. THE Decision_Explanation card SHALL display: the triggering metric values (current ACoS, target ACoS, spend, sales, clicks, orders), the personality policy parameters applied (lookbackDays, cooldownHours, thresholds), and the safety boundary that was checked.
2. THE Decision_Explanation card SHALL display: the rule that fired (e.g., "ACoS 32% exceeds target 25% by tolerance ratio 1.2"), the proposed action (e.g., "Decrease bid from $1.50 to $1.35"), and the predicted impact estimate.
3. THE Decision_Explanation card SHALL display the full audit trail: operation creation timestamp, approval/rejection events, submission timestamp, platform response (Amazon request ID), read-after-write verification result, and effect attribution results.
4. THE Decision_Explanation card SHALL display the AI_Personality used, the rule_version of the personality policy, and the source level that determined the personality (campaign/goal/store/system).
5. THE Decision_Explanation card SHALL be accessible from: the Hosting_Dashboard decision list, the campaign detail operations tab, and the approval workflow detail view.
6. THE System SHALL generate Decision_Explanation data, backed by the immutable Decision_Snapshot (Requirement 34), as a structured JSON field on the Operation_Record at creation time, so explanations are immutable audit records not reconstructed later.

### Requirement 14: Amazon External ID Mapping and Entity Sync

**User Story:** As the system, I want a reliable bidirectional mapping between internal AdPilot UUIDs and Amazon external IDs, populated by a proper entity sync, so that write operations target the correct Amazon entities and metric rows attach to complete entities.

**Phase:** 1 (Foundation)

#### Acceptance Criteria

1. THE System SHALL reuse the existing `external_entity_mappings` table (store_id, platform, internal_entity_type, internal_entity_id, external_entity_type, external_entity_id, external_data, last_synced_at) rather than creating a new mapping table.
2. WHEN the AmazonAdsWriteConnector needs to submit a change for an internal entity, THE System SHALL resolve the entity's Amazon external ID from `external_entity_mappings`; IF no mapping exists, THEN THE System SHALL return a rejected result with reason "NO_EXTERNAL_MAPPING".
3. THE System SHALL add a reverse-direction unique constraint `UNIQUE(store_id, platform, external_entity_type, external_entity_id)` to `external_entity_mappings`, complementing the existing internal-direction unique constraint, to prevent duplicate external mappings.
4. THE Entity_Sync_Job SHALL pull Amazon Ads campaign, ad group, and keyword **metadata** (names, states, hierarchy) and upsert complete local entities plus their `external_entity_mappings`, marking entities created from Amazon with origin `amazon_import`. Metric ingestion (Requirement 2) SHALL NOT create entities.
5. WHEN an entity is created locally and later submitted to Amazon, THE System SHALL update its mapping with the Amazon-assigned external_id from the read-after-write verification result.
6. WHEN a report or metric row references an Amazon entity ID that cannot be resolved to a complete local entity, THE System SHALL store the row in a `metric_quarantine` table for later reconciliation once entity sync catches up, rather than fabricating a partial entity.

### Requirement 15: Rate Limiting and Token Management

**User Story:** As the system, I want robust rate-limit handling and token lifecycle management for Amazon Ads API calls, so that the connector operates reliably within Amazon's constraints.

**Phase:** 2 (Write Integration)

#### Acceptance Criteria

1. THE AmazonAdsWriteConnector SHALL maintain a per-profile rate limiter that tracks API calls against Amazon's published rate limits (configurable; default 10 requests/second).
2. WHEN the rate limiter determines a call would exceed the limit, THE rate limiter SHALL return the next available time rather than blocking; the Outbox SHALL set the Outbox row's `next_attempt_at` accordingly and re-claim later. The connector SHALL NOT sleep/wait internally for rate-limit windows — consistent with the single retry owner (Requirement 30.7).
3. THE System SHALL cache LWA access tokens per PlatformConnection and refresh them proactively when within 5 minutes of expiration, using the stored refresh_token_encrypted (decrypted via CryptoUtil).
4. WHEN a token refresh fails (invalid_grant), THE System SHALL mark the PlatformConnection as status `token_expired`, cease all API calls for that connection, and send a Feishu notification alerting the operator to re-authorize.
5. THE System SHALL log all API calls (excluding credential values) with: endpoint, HTTP status, latency_ms, operation_id, and retry_count for operational monitoring.
6. WHEN the System detects sustained rate limiting (more than 10 rate-limited responses in 5 minutes), THE System SHALL reduce request frequency by 50% for 15 minutes (adaptive backoff).

### Requirement 16: Single-Item Submission and Future Batch Support

**User Story:** As the system, I want write submission to match the existing single-item connector SPI, so that the connector contract is not violated, while leaving a clear path to batch support.

**Phase:** 2 (Write Integration)

#### Acceptance Criteria

1. THE AmazonAdsWriteConnector SHALL submit each Operation as a **single** `PlatformChange` via `submit(ConnectionContext, PlatformChange)` and return exactly one `PlatformWriteResult`, consistent with the existing `PlatformWriteConnector` SPI (which neither accepts nor returns collections).
2. THE System SHALL NOT require the single-value `submit` method to return multiple results; per-Operation submission is the supported model for the initial phase.
3. IF batch submission is later required, THEN THE System SHALL add an explicit `submitBatch(ConnectionContext, List<PlatformChange>)` method returning a `BatchPlatformWriteResult` (per-item accepted/rejected outcomes) as a deliberate SPI extension, rather than overloading `submit` semantics.
4. WHEN multiple Operations target the same Amazon entity in one run, THE System SHALL submit them individually and record each Operation's own platform_result. A successfully accepted Operation advances to `submitted`; a permanently rejected Operation advances to `failed` with the reason recorded; a transiently-failed (`retryable=true`) Operation is **left in place** for the Outbox to re-attempt (see 16.7) and is NOT silently abandoned.
5. THE System SHALL extend `PlatformWriteResult` (currently a 3-field record: accepted, platformReference, message) with **structured** fields for: the Amazon request ID, the affected external entity ID, the platform error code, a `retryable` boolean, and a `retryAfter`/backoff hint (Requirements 1.8, 1.9), rather than packing those values into the free-text message string.
6. THE System SHALL extend the connector SPI to support read-after-write verification with full context, choosing one of: (a) a `verify(ConnectionContext, PlatformChange, SubmissionMetadata)` method returning the structured live field value(s); or (b) a flow where the StatusPoller loads the Operation and passes the entity type, field, and expected value, and the connector returns structured live values for the business layer to compare. The existing single-argument `queryStatus(ConnectionContext, String)` SHALL NOT be relied on for value comparison, as it lacks entity type, field, and expected value.
7. THE System SHALL model transport/transient retries WITHOUT creating new Operations and WITHOUT prematurely entering `submitted`: an Operation SHALL only advance to `submitted` once the platform has actually **accepted** the request. While the request has not yet been accepted, retry state SHALL live on the **Outbox row** via `attempt_count`, `next_attempt_at`, and `last_error` (the System SHALL add these columns to `operation_outbox`); the same Operation and the same Outbox row are reused across attempts. WHEN a `retryable=true` result is returned, the Outbox SHALL increment `attempt_count`, set `next_attempt_at = now + retryAfter`, record `last_error`, and leave the Operation in its pre-submission state for re-claim. The System MAY introduce a transient `submitting` state to represent "claimed, call in progress" distinctly from `pending`; only a confirmed platform acceptance transitions to `submitted`, and only a permanent rejection transitions to `failed` (the `failed → pending` RETRY edge is reserved for an operator/manual retry of a permanently failed Operation, not for transport retries).

### Requirement 17: Hosting Phase Configuration

**User Story:** As an administrator, I want to configure which optimization phases (V1/V2/V3) are active, so that new capabilities can be enabled incrementally and rolled back if issues arise.

**Phase:** 3 (Phase Management)

#### Acceptance Criteria

1. THE System SHALL store the active hosting phase in a configurable system setting with values: `V1` (bid only), `V2` (bid + budget), `V3` (bid + budget + keywords).
2. WHEN the active phase is V1, THE V2_Budget_Engine and V3_Keyword_Engine SHALL not execute any optimization runs.
3. WHEN the active phase is V2, THE V3_Keyword_Engine SHALL not execute, but THE V2_Budget_Engine SHALL run alongside the existing V1 bid optimizer.
4. WHEN the active phase is V3, all three engines SHALL run.
5. THE System SHALL allow phase changes via an admin API endpoint that validates the transition (only forward V1→V2→V3 or backward V3→V2→V1) and records the change in audit_logs.
6. WHEN the phase is downgraded (e.g., V3→V2), THE System SHALL, for the disabled engine: cancel any `awaiting_approval` Operations (via the `CANCEL` event), transition in-scope `pending` Operations to `superseded`/`cancelled` and close their Outbox rows so they are not submitted, and cease generating new operations for disabled capabilities. Already `submitted`/in-flight Operations follow platform cancel or reconciliation rather than local force-cancel.

### Requirement 18: Optimization Run Logging

**User Story:** As an operator, I want a complete log of each optimization run, so that I can audit the AI's behavior.

**Phase:** 3 (Observability)

#### Acceptance Criteria

1. THE System SHALL create an `optimization_runs` record for each scheduled or manual optimization execution, containing: run_id, trigger_type (scheduled/manual), phase, start_time, end_time, store_id, and a reference to the immutable data snapshot used.
2. THE optimization run record SHALL include per-campaign results: campaigns_evaluated, campaigns_skipped (with skip reasons), decisions_generated, decisions_auto_executed, decisions_requiring_approval.
3. WHEN a campaign is skipped, THE System SHALL record the skip reason (DATA_STALE, DATA_INCOMPLETE, COOLDOWN, NO_TARGET_ACOS, LEARNING_PERIOD, NOT_HOSTED, OBSERVE_ONLY, KILL_SWITCH) in the run detail.
4. THE Hosting_Dashboard SHALL display a link to the most recent optimization run with summary statistics.
5. THE System SHALL retain optimization run records for at least 90 days for audit purposes.

### Requirement 19: Learning Period Enforcement

**User Story:** As the system, I want to enforce a learning period after a campaign is first hosted or significantly changed, so that the AI accumulates sufficient data before aggressive adjustments.

**Phase:** 4 (Safety)

#### Acceptance Criteria

1. WHEN a campaign's AI_Hosting_Status changes to `hosted`, THE System SHALL record the hosting_start_date and begin a learning period (duration from the personality policy's learningPeriodDays, default: 3 days).
2. WHILE a campaign is within its learning period, THE optimization engine SHALL only propose conservative adjustments: bid changes capped at 10% regardless of personality, and no budget changes.
3. WHEN the learning period completes, THE System SHALL allow the full personality-driven optimization rules to apply.
4. WHEN a campaign's personality is changed, THE System SHALL restart the learning period for that campaign.
5. THE Hosting_Dashboard SHALL display learning-period status for campaigns currently in their learning period, showing days remaining.

### Requirement 20: Write Verification and Eventual Consistency Handling

**User Story:** As the system, I want to confirm Amazon Ads changes via read-after-write verification, so that operations are marked effective only when the live value actually matches, and are not falsely failed during propagation delay.

**Phase:** 3 (Reliability)

#### Acceptance Criteria

1. WHEN an Operation is submitted and the immediate API response indicates acceptance, THE System SHALL set the SyncState to `submitted`, persist the Amazon request ID and external entity ID (as structured fields per Requirement 16.5), and schedule a read-after-write verification (via the extended SPI of Requirement 16.6) after a configurable initial delay (default: 60 seconds).
2. WHEN verification re-reads the entity and the live field value matches the Operation's `after_value`, THE System SHALL transition the Operation to `effective`.
3. WHEN verification re-reads the entity and the live value does NOT match (and is not still the old value within a tolerated propagation window), THE System SHALL transition the Operation to `reconciliation_required` rather than blindly marking it effective or failed.
4. WHEN verification cannot read the entity or the value is unchanged after the configured retries/timeout (default total 15 minutes), THE System SHALL transition the Operation to `expired` with statusReason "VERIFY_TIMEOUT"; `expired` is not terminal and a later callback or successful re-read may transition it to `effective`, `failed`, or `reconciliation_required`.
5. THE System SHALL not submit a new conflicting Operation for the same entity field while a previous Operation is in any unsettled state, using the existing InFlightConflictLock.

### Requirement 21: Store-Level Hosting Configuration API

**User Story:** As an operator, I want to configure hosting settings per store via API, so that frontend settings persistence works correctly and configurations are consistent.

**Phase:** 5 (Settings)

#### Acceptance Criteria

1. THE Backend SHALL expose `GET /api/advertising/hosting/config/{storeId}` returning: active_phase, default_personality, execution_mode, auto_execute_threshold, notification_preferences, and boundary overrides.
2. THE Backend SHALL expose `PUT /api/advertising/hosting/config/{storeId}` that accepts and persists the hosting configuration, validating all fields.
3. WHEN the PUT endpoint receives an auto_execute_threshold outside 0.0–1.0, THE System SHALL reject with HTTP 400 and a descriptive error.
4. WHEN the PUT endpoint receives boundary values that violate the only-tighten constraint relative to the organization or system level (per each limit's comparison_semantics), THE System SHALL reject with HTTP 400 indicating which boundary is violated.
5. THE Backend SHALL expose `GET /api/advertising/hosting/config/{storeId}/goals/{goalId}` and `GET /api/advertising/hosting/config/{storeId}/campaigns/{campaignId}` for goal- and campaign-level overrides.
6. THE Backend SHALL enforce `advertising:manage` for reading hosting config and `advertising:execute` for modifying it.

### Requirement 22: Brand Word Protection Configuration

**User Story:** As an operator, I want to configure brand words per store that the AI will never add as negatives, so that brand traffic is always protected.

**Phase:** 8 (V3 Protection)

#### Acceptance Criteria

1. THE System SHALL maintain a `brand_word_lists` table with columns: id, store_id, word (VARCHAR 255), match_type (exact/contains), created_by, created_at.
2. THE Backend SHALL expose CRUD endpoints at `/api/advertising/hosting/brand-words/{storeId}`: GET (list), POST (add), DELETE (remove).
3. WHEN the V3_Keyword_Engine evaluates a search term for negative addition, THE System SHALL check the term against all brand words for that store using the configured match_type (exact = full match, contains = substring match).
4. IF a proposed negative matches a brand word, THEN THE System SHALL reject the proposal, log the match, and record "BRAND_PROTECTED" in the optimization run detail.
5. THE System SHALL allow bulk import of brand words via CSV upload (one word per line).
6. WHEN a brand word is added or removed, THE System SHALL record the change in the audit log.

### Requirement 23: Optimization Engine Coordination

**User Story:** As the system, I want the V1/V2/V3 engines to read a single data snapshot and feed a coordinator that de-conflicts and clips decisions, so they never produce conflicting or circular decisions.

**Phase:** 6 (Engine Coordination)

#### Acceptance Criteria

1. WHEN an optimization run starts, THE System SHALL capture a single **immutable data snapshot** and provide it to all active engines (V1 bid, V2 budget, V3 keywords); every engine reads the same point-in-time data.
2. THE engines SHALL each produce **candidate decisions** independently from the snapshot (no engine consumes another engine's not-yet-created Operations); the Optimization_Coordinator SHALL then de-conflict, prioritize, apply safety-boundary clipping (including cross-engine interactions such as a budget decrease constraining bid increases), and only afterward create Operations.
3. THE Optimization_Coordinator SHALL enforce per-campaign `maxOperationsPerRun`: if candidate decisions for a campaign exceed it, THE System SHALL prioritize by risk score (lowest-risk first) and defer the rest. It SHALL separately enforce `maxOperationsPerDay`, counting all Operations created for the campaign across all runs within the marketplace-timezone day.
4. WHEN a candidate decision conflicts with an existing unsettled Operation (same entity, same field), THE Optimization_Coordinator SHALL skip the new decision and log the conflict rather than creating a competing operation.

### Requirement 24: Dashboard Approval Workflow Integration

**User Story:** As an operator, I want to approve or reject AI decisions directly from the hosting dashboard.

**Phase:** 7 (Dashboard)

#### Acceptance Criteria

1. THE Hosting_Dashboard decision list SHALL display "Approve" and "Reject" buttons for each operation in `awaiting_approval`, visible only to users with `advertising:approve`.
2. WHEN the user clicks "Approve", THE System SHALL apply the `APPROVE` event to advance the Operation from `awaiting_approval` to `pending`, so the `OutboxWorker` claims it and performs the `pending → submitted` SUBMIT (the Outbox only processes `pending`; approving directly to `submitted` would never be picked up).
3. WHEN the user clicks "Reject", THE System SHALL display a reason input, apply the `REJECT` event to advance the Operation to `cancelled` with the provided reason, and notify the originator.
4. THE Hosting_Dashboard SHALL display a count badge of decisions awaiting the current user's approval.
5. WHEN an operation is approved or rejected from the dashboard, THE System SHALL update the decision list in real-time without a full page reload.

### Requirement 25: Inventory-Aware Safety Check

**User Story:** As the system, I want optimization engines to factor in inventory, so that the AI never increases spend on products running out of stock.

**Phase:** 6 (Inventory Safety)

#### Acceptance Criteria

1. WHEN the V1 bid optimizer or V2 budget engine evaluates a campaign, THE System SHALL query the inventory module for the campaign's associated products' available_inventory_days.
2. IF any product has available_inventory_days below the safety boundary's inventorySafetyDays (configurable, default: 7), THEN THE System SHALL reduce the maximum allowed bid/budget increase to 0% and flag the campaign for monitoring.
3. IF any product has available_inventory_days below the safety boundary's `inventoryCriticalDays` (configurable, default: 3), THEN THE System SHALL: (a) immediately and automatically apply the internal AI Kill Switch for the campaign (no approval), per Requirement 6.5(a); and (b) propose an Amazon-facing budget reduction (configurable, default 50%) with risk_score 0.9 routed per the campaign's Execution_Mode (auto-execute or approval per Requirement 6.5(b)).
4. THE System SHALL use product-campaign associations from the campaign_product_links table.
5. IF inventory data is unavailable, THE System SHALL log a warning and treat it as fail-closed (no increases permitted) per the data-staleness rule.

### Requirement 26: API Error Standardization

**User Story:** As a frontend developer, I want consistent error responses from all hosting API endpoints.

**Phase:** 1 (Foundation)

#### Acceptance Criteria

1. THE Backend hosting endpoints SHALL return errors using the existing ApiResponse envelope with structured codes `HOSTING_{CATEGORY}_{DETAIL}` (e.g., HOSTING_DATA_STALE, HOSTING_BOUNDARY_VIOLATION, HOSTING_TOKEN_INVALID).
2. WHEN a hosting operation fails due to a safety boundary violation, THE System SHALL return HTTP 422 with code HOSTING_BOUNDARY_VIOLATION and a message identifying the boundary.
3. WHEN a hosting operation fails due to data quality, THE System SHALL return HTTP 422 with code HOSTING_DATA_INSUFFICIENT and the specific data gap.
4. THE System SHALL distinguish synchronous from asynchronous failures: synchronous parameter/boundary validation failures on a trigger SHALL return HTTP 400/422; a successful optimization trigger SHALL return HTTP 202 with a run_id; and Amazon Ads API failures that occur during asynchronous Operation execution SHALL NOT be reported as an HTTP response on the trigger call — they SHALL be recorded on the Operation and in the optimization run result. Only direct, synchronous hosting endpoints that themselves call Amazon SHALL return HTTP 502 (code HOSTING_PLATFORM_ERROR) including the Amazon error code.
5. THE System SHALL include a request_id in all error responses.

### Requirement 27: Hosting Dashboard Summary Statistics API

**User Story:** As the frontend, I want a single API call for all dashboard summary statistics.

**Phase:** 7 (Dashboard)

#### Acceptance Criteria

1. THE Backend SHALL expose `GET /api/advertising/hosting/dashboard/summary?storeId={storeId}` returning: hosted_campaigns_count, today_decisions_count, awaiting_approval_count, effective_today_count, failed_today_count, estimated_savings_7d, estimated_savings_30d.
2. THE summary endpoint SHALL compute "today" using the store's marketplace timezone.
3. THE summary endpoint SHALL compute estimated_savings from effect_attributions where the operation improved ACoS and the estimated_incremental_impact on spend is negative, labeled as an estimate.
4. THE summary endpoint SHALL respond within 500ms for stores with up to 1000 campaigns using indexed queries and optional Redis caching (TTL: 60 seconds).
5. THE summary endpoint SHALL respect data-scope permissions.

### Requirement 28: Optimization Run Manual Trigger

**User Story:** As an operator, I want to manually trigger an optimization run for a store or campaign.

**Phase:** 5 (Operations)

#### Acceptance Criteria

1. THE Backend SHALL expose `POST /api/advertising/hosting/optimize/trigger` accepting storeId and optional campaignId, requiring `advertising:execute`. Synchronous validation failures SHALL return HTTP 400/422; a successful trigger SHALL return HTTP 202 with a run_id (asynchronous platform failures are reported on the Operation/run result, not on this response — Requirement 26.4).
2. WHEN triggered for a store without a campaignId, THE System SHALL run optimization for all hosted campaigns in that store, respecting the active phase.
3. WHEN triggered for a specific campaign, THE System SHALL run optimization only for that campaign.
4. THE System SHALL enforce a minimum interval between manual triggers (default: 5 minutes per store).
5. THE endpoint SHALL return an optimization run_id for polling completion and results.
6. WHEN a manual trigger is executed, THE System SHALL log it in audit_logs with the acting user and scope.
7. THE Backend SHALL expose `GET /api/advertising/hosting/optimization-runs/{runId}` returning the run's status, per-campaign results, and any decisions/operations generated, so the frontend can poll a triggered run to completion.

### Requirement 29: Historical Decision Analytics

**User Story:** As an operator, I want historical AI decision analytics to evaluate the AI's effectiveness.

**Phase:** 10 (Analytics)

#### Acceptance Criteria

1. THE Backend SHALL expose `GET /api/advertising/hosting/analytics?storeId={storeId}&period={7d|30d|90d}` returning aggregated metrics.
2. THE analytics endpoint SHALL return: total_decisions, auto_executed_count, approval_required_count, success_rate (effective / total attempted), average_risk_score, top_failure_reasons (array of {reason, count}).
3. THE analytics endpoint SHALL return effect attribution aggregates: average_acos_improvement, total estimated_spend_saved, total estimated_sales_lift, and average_attribution_confidence; all impact figures SHALL be presented as estimates derived from estimated_incremental_impact, not raw observed change.
4. THE analytics endpoint SHALL break down decisions by engine type (V1_bid, V2_budget, V3_keyword) showing per-engine success rates and estimated impact.
5. THE Hosting_Dashboard SHALL display a chart of decision volume and success rate trends over the selected period.

### Requirement 30: Graceful Degradation

**User Story:** As the system, I want the AI hosting system to degrade gracefully when dependencies fail, without cascading failures or executing stale decisions.

**Phase:** 3 (Reliability)

#### Acceptance Criteria

1. IF the Amazon Ads API is unreachable, THEN THE System SHALL keep decisions in `pending` with a `decision_expires_at` deadline (Requirement 33) and SHALL NOT advance them to `submitted`. The `OutboxWorker` SHALL check an Amazon circuit-breaker BEFORE claiming a row; when the breaker is open, the worker SHALL set the Outbox row's `next_attempt_at` and leave the Operation `pending` (it must not perform the `pending → submitted` SUBMIT before the network is reachable, since that transition currently happens before the platform call).
2. IF the inventory service is unavailable, THEN THE System SHALL log a warning, apply fail-closed behavior (no increases), and continue with reduced capability.
3. IF Redis is unavailable, THEN THE System SHALL fall back to database-based rate limiting and token caching with degraded performance rather than halting.
4. IF the Feishu integration is unreachable, THEN THE System SHALL queue notifications for later delivery and continue optimization/execution without blocking.
5. THE System SHALL expose `GET /api/advertising/hosting/health` reporting each dependency (amazon_api, inventory_service, redis, feishu) as healthy/degraded/unavailable.
6. WHEN a dependency transitions to degraded or unavailable, THE System SHALL record the transition and send a Feishu notification (if Feishu is available).
7. THE System SHALL designate the **Outbox** as the single retry owner: the connector performs exactly one platform call per invocation and returns a `retryable` flag plus an optional `retryAfter`/backoff hint (Requirements 1.8, 1.9); the Outbox schedules all delayed retries via `next_attempt_at`/`attempt_count`. Worker threads SHALL NOT block by sleeping on a `Retry-After` value, and the connector SHALL NOT loop across attempts internally.

### Requirement 31: Search Term Daily Data Model

**User Story:** As the system, I want a dedicated daily-grain store for search term performance, so that the V3 engine has the data it needs and the existing performance model is not overloaded.

**Phase:** 1 (Foundation)

#### Acceptance Criteria

1. THE System SHALL persist search term performance at daily grain in a **dedicated `search_term_daily` table** (not by overloading the `performance_daily` entity_type), keyed uniquely by (store_id, campaign_id, ad_group_id, search_term, report_date).
2. THE `search_term_daily` table SHALL carry the same data-quality columns as performance data: currency, data_status (preliminary/finalized), data_version, and updated_at.
3. THE `search_term_daily` table SHALL retain enough history to satisfy the largest personality policy lookbackDays plus the attribution backfill window.
4. THE V3_Keyword_Engine SHALL read exclusively from `search_term_daily` when scoring search terms.

### Requirement 32: Performance Data Model Hardening

**User Story:** As the system, I want the performance data model to enforce uniqueness, support attribution backfill, and carry currency and data-version metadata, so that ingestion is idempotent and attribution is accurate.

**Phase:** 1 (Foundation)

#### Acceptance Criteria

1. THE System SHALL add a unique constraint `UNIQUE(store_id, entity_type, entity_id, report_date)` to the performance data store to guarantee idempotent upserts. The date column SHALL be named `report_date` consistently across the schema and all requirements (not `date`).
2. THE System SHALL add an `updated_at` column (ON UPDATE) to support attribution-driven backfill of revised rows.
3. THE System SHALL add a `currency` column to performance rows.
4. THE System SHALL add a `data_status` column (`preliminary`/`finalized`) and a `data_version` column to distinguish in-flux from settled data and to track re-ingestion.
5. THE search-term data SHALL live in the dedicated `search_term_daily` table (Requirement 31); the `performance_daily.entity_type` CHECK constraint SHALL NOT be widened to include `search_term`.

### Requirement 33: Decision Expiry and Pre-Submission Revalidation

**User Story:** As the system, I want queued decisions to expire and be revalidated before submission, so that stale decisions are never executed after a delay or outage.

**Phase:** 3 (Reliability)

#### Acceptance Criteria

1. THE System SHALL set a `decision_expires_at` timestamp on every AI Operation at creation (configurable TTL, default: 4 hours).
2. BEFORE submitting any queued Operation, THE System SHALL re-run the Data_Quality_Gate and re-resolve safety boundaries against current data.
3. IF an Operation's `decision_expires_at` has passed, OR pre-submission revalidation fails, THEN THE System SHALL transition the Operation to `superseded` (or `expired` where appropriate) and SHALL NOT submit it.
4. WHEN connectivity is restored after an outage, THE System SHALL NOT blindly submit accumulated decisions; each SHALL pass expiry and revalidation checks first.
5. THE System SHALL record expiry/supersession with a reason in the optimization run log and Operation audit trail.

### Requirement 34: Immutable Decision Input Snapshot

**User Story:** As an auditor, I want each AI Operation to immutably capture the full inputs and reasoning at creation time, so that every decision is fully reproducible and explainable.

**Phase:** 3 (Foundational Audit — must be in place before any AI Operation executes)

#### Acceptance Criteria

1. WHEN an AI Operation is created, THE System SHALL persist an immutable Decision_Snapshot JSON containing: the data cutoff timestamp and lookback window; the metric input snapshot (the actual numbers used); the Data_Quality_Gate result; the resolved personality and its inheritance source chain; each effective safety boundary and its source level; the Risk_Score formula version and computed result; the rule_version; the currency; and the marketplace timezone.
2. THE Decision_Snapshot SHALL be write-once and SHALL NOT be mutated after creation; later events (approval, submission, verification, attribution) SHALL be appended as separate audit entries, not edits to the snapshot.
3. THE Decision_Snapshot SHALL be the source for the Decision_Explanation card (Requirement 13) so explanations are never reconstructed from live data.
4. THE System SHALL include the Execution_Mode and any Kill_Switch/Shadow_Mode flags in effect at creation time in the snapshot.

### Requirement 35: Production Rollout and Governance (Phase 11)

**User Story:** As an administrator, I want a controlled production rollout with kill switches, shadow mode, and monitoring, so that the AI hosting system can be enabled safely and stopped instantly if needed.

**Phase:** 11 (Production Rollout & Governance)

#### Acceptance Criteria

1. THE System SHALL support a Shadow_Mode in which engines generate and persist decisions and explanations but never submit to Amazon, for validating behavior against production data.
2. THE System SHALL support canary rollout: enabling hosting for a designated subset of stores before org-wide enablement.
3. THE System SHALL provide Kill_Switches at System, organization, store, and campaign levels that immediately stop new decision generation and submission for the scoped entities. Activating a kill switch SHALL be recorded in audit_logs and SHALL: (a) cancel `awaiting_approval` Operations in scope; (b) transition in-scope `pending` Operations to `superseded`/`cancelled` and close their corresponding Outbox rows so the Outbox cannot submit them; and (c) for already `submitted`/in-flight Operations, NOT force-cancel locally but route them through platform cancel (where supported) or status reconciliation. The Outbox and engines SHALL re-check the kill switch, active phase, and Execution_Mode immediately before submitting any Operation.
4. THE System SHALL define and monitor SLOs (e.g., report sync freshness, submission success rate, verification latency) and raise alerts when SLOs are breached.
5. THE System SHALL monitor decision drift (sustained shifts in decision volume, average risk score, or rejection rate) and alert when drift exceeds configured thresholds.
6. THE System SHALL enforce an audit retention policy for Operations, Decision_Snapshots, optimization runs, and attribution records (minimum 90 days, configurable longer for compliance).
7. THE System SHALL support a rollback drill: a documented, testable procedure to mass-compensate or pause recent AI changes for a store.
8. THE System SHALL define production acceptance criteria (shadow-mode agreement rate, canary success metrics, SLO compliance) that gate progression from shadow → canary → full rollout.

### Requirement 36: V1 Bid Engine Hardening (Keyword-Level)

**User Story:** As an operator, I want the V1 bid engine to optimize bids using real keyword-level performance, so that each keyword's bid reflects its own ACoS rather than a single campaign-wide number.

**Phase:** 3 (must complete before V1 runs in any executing mode)

#### Acceptance Criteria

0. UNTIL Requirement 36 is complete, THE V1 bid engine SHALL only run in `observe_only`/`shadow` mode (it SHALL NOT execute against Amazon using the legacy campaign-aggregated ACoS logic), so the existing incorrect behavior is never used for live changes.

1. THE V1 bid engine SHALL compute each keyword's recent ACoS from **keyword-level** performance data (performance rows for that keyword), replacing the current behavior in `AiHostingOptimizer` that aggregates ACoS at the campaign level and applies the same value to every keyword.
2. WHEN a keyword has insufficient keyword-level data for the lookback window, THE V1 bid engine SHALL skip that keyword (recording the skip reason) and SHALL NOT fall back to the campaign-aggregated ACoS as a substitute.
3. THE V1 bid engine SHALL run against the single immutable data snapshot and emit candidate decisions to the Optimization_Coordinator (Requirement 23) rather than creating Operations directly.
4. THE V1 bid engine SHALL pass through the Data_Quality_Gate (Requirement 3), produce a Risk_Score (Requirement 7), and persist a Decision_Snapshot (Requirement 34) for every candidate it promotes.
5. THE V1 bid engine SHALL honor the personality policy's adjustmentCooldownHours at keyword grain and SHALL use a keyword-level in-flight conflict lock so it never creates a competing Operation for a keyword that already has an unsettled bid Operation.
6. THE V1 bid engine SHALL clamp every proposed bid within the resolved Safety_Boundary's minBid, maxBid, maxCpc, and maxBidAdjustmentRatio.

### Requirement 37: AI Decision Storage Model (Observe/Recommend Modes)

**User Story:** As the system, I want a dedicated store for AI decisions that are not (yet) Operations, so that observe_only and recommend_only decisions can be persisted, explained, and reported.

**Phase:** 5 (Risk & Approval)

#### Acceptance Criteria

1. THE System SHALL maintain an `ai_decisions` table to persist every decision regardless of Execution_Mode, with at least: decision_id, run_id, store_id, campaign_id, engine_type (V1_bid/V2_budget/V3_keyword), execution_mode, decision_status, candidate_action (entity, field, before/after), risk_score, decision_snapshot (JSON, Requirement 34), expires_at, created_at, and promoted_operation_id (nullable).
2. WHEN Execution_Mode is `observe_only` or `recommend_only`, THE System SHALL persist the decision in `ai_decisions` and SHALL NOT create an Operation; the dashboard and analytics SHALL read these decisions from `ai_decisions`.
3. WHEN Execution_Mode is `approval_required` or `auto_execute`, THE System SHALL create the Operation by routing the decision through the full Requirement 7 logic, including the fixed precedence pipeline of Requirement 7.10 (Kill Switch → Shadow → phase → Execution_Mode → high-risk gate → risk threshold), and set `ai_decisions.promoted_operation_id` to link the decision to the resulting Operation — it SHALL NOT unconditionally create a `pending` Operation, since the same decision may instead be routed to `awaiting_approval`.
4. THE analytics and dashboard endpoints (Requirements 11, 27, 29) SHALL compute counts and rates over `ai_decisions` (joined to operations where promoted), so observe/recommend decisions are included in statistics.
5. THE `ai_decisions.decision_snapshot` SHALL be the same immutable snapshot defined in Requirement 34, written once at decision creation.

### Requirement 38: Personality Policy Scoping

**User Story:** As an administrator, I want personality numeric policies to be scoped correctly, so that a store-level policy applies to one store rather than leaking globally.

**Phase:** 4 (Safety Enhancement)

#### Acceptance Criteria

1. THE System SHALL extend the `personality_policies` model so a policy is keyed by `(scope_type, scope_id, personality, rule_version)`; the current `UNIQUE(scope, personality)` (with no scope_id) causes a "store" policy to behave as a single global policy shared by all stores.
2. THE System SHALL add `status` and `effective_from`/`effective_to` columns to `personality_policies`, and SHALL enforce that at most ONE `active` version exists per `(scope_type, scope_id, personality)` at any time, so version selection is unambiguous.
3. WHEN resolving a personality policy for a campaign, THE System SHALL select the active version for the most specific scope match (campaign_id → goal_id → store_id → organization_id → system) for the resolved personality.
4. WHEN an AI Operation (or `ai_decisions` row) is created, THE System SHALL freeze the chosen `policy_id` and `rule_version` into the Decision_Snapshot, so later policy changes never retroactively alter how an existing decision is interpreted.
5. IF the product decision is to keep personality numeric policies system-global only, THEN THE System SHALL state that explicitly and SHALL reject creation of store/goal/campaign-scoped policy rows, rather than silently treating a `scope='store'` row as global.
6. WHEN a scoped policy is added or changed, THE System SHALL record the change in the audit log with the scope_type, scope_id, personality, and rule_version.

### Requirement 39: Cross-Organization Isolation

**User Story:** As a security-conscious operator, I want every hosting API and job to enforce organization boundaries, so that no user can read, approve, trigger, or roll back anything outside their organization.

**Phase:** 1 (Foundation)

#### Acceptance Criteria

1. THE System SHALL validate that every inbound `storeId`, `campaignId`, `goalId`, `operationId`, `runId`, and `decisionId` belongs to the caller's current organization before performing any read, configuration, approval, manual trigger, rollback, or run-detail operation; a mismatch SHALL return HTTP 403/404 (not a cross-org leak).
2. THE new tables introduced by this spec (`safety_boundaries`, `brand_word_lists`, `ai_decisions`, `report_sync_runs`, `effect_attributions`, `metric_quarantine`, and any hosting config tables) SHALL either persist `org_id` directly or be constrained to an `org_id` via a non-null `store_id` foreign key, so org scoping is enforceable at the data layer.
3. THE System SHALL forbid cross-organization access for all hosting operations: dashboard/analytics reads, settings reads/writes, brand-word CRUD, manual optimization triggers, approvals/rejections, rollbacks, and optimization-run detail queries SHALL all be org-scoped, layered on top of the existing data-scope (store-level) permissions.
4. THE System SHALL include cross-organization access tests (negative tests) verifying that a user from organization A cannot read, configure, approve, trigger, or roll back resources belonging to organization B.
5. WHERE the existing data-scope mechanism already enforces store-level visibility, THE org check SHALL be applied in addition to (not instead of) data-scope, so both boundaries hold.

### Requirement 40: Existing Approval Workflow Integration

**User Story:** As an operator, I want AI Operation approvals to use the platform's existing approval workflow, so that approvals follow the same rules, permissions, and audit trail as the rest of the system.

**Phase:** 5 (Risk & Approval)

#### Acceptance Criteria

1. WHEN an Operation is created in `awaiting_approval`, THE System SHALL create a corresponding `approval_requests` record with `related_entity_id = operation_id` (and related_entity_type identifying it as a hosting Operation), reusing the existing approval infrastructure rather than inventing a parallel one.
2. THE System SHALL keep the `approval_requests` status and the Operation's SyncState synchronized atomically: approving the request and transitioning the Operation `awaiting_approval → pending` (Requirement 7.7) SHALL occur in a single transaction, as SHALL rejecting and transitioning to `cancelled`.
3. THE System SHALL designate the **Operation SyncState** as the authoritative execution state; the `approval_requests` record drives the human decision but SHALL NOT diverge from the Operation's state.
4. THE System SHALL route approvals using the existing risk-level approval policies and permissions (e.g., `advertising:approve`), and SHALL forbid the decision's originator from approving their own decision (no self-approval).
5. WHEN an approval request expires per the existing approval policy, THE System SHALL transition the linked Operation to `expired`/`cancelled` (with reason "APPROVAL_EXPIRED") rather than leaving it indefinitely in `awaiting_approval`.
6. WHEN an Operation is cancelled/superseded by a kill switch, phase downgrade, or decision expiry (Requirements 33, 35, 17), THE System SHALL close any open linked `approval_requests` with the corresponding reason.

---

## External Dependencies (Owned by Other Specs)

> The AI Hosting System's dashboards, settings, and approval flows ride on platform-wide auth, API-client, permission, and navigation foundations. The following platform-hardening and UX concerns are **prerequisites** for this spec but are **owned by the `project-fix-and-cleanup` and `platform-ux-logistics-enhancements` specs** — they are declared here as dependencies only and SHALL NOT be implemented within this hosting spec (to avoid one spec owning both the advertising system and a full-platform UI refactor).

### Requirement 41: Platform Hardening Prerequisites (Dependency Declaration)

**User Story:** As the AI Hosting System, I depend on platform-level security and correctness fixes being in place, so that my UI and APIs are safe and honest.

**Phase:** 0 (Prerequisite — tracked in `project-fix-and-cleanup`)

#### Acceptance Criteria

1. THE hosting spec SHALL depend on (and SHALL NOT re-specify) the following items, which SHALL be owned and implemented by `project-fix-and-cleanup`:
   - Enforced authorization on currently-unguarded routes (home, stores, system settings, API connections, sync logs) and their controllers (`StoreController`, `ApiSyncController`, `AiSettingsController`), so menu hiding is never the only access control.
   - Correct 401-vs-403 handling (401 → refresh/logout; 403 → no-permission view without clearing the session).
   - Removal of false-success interactions (the `ChangePasswordPage` demo fallback; the simulated save in `SettingsPage`), disabling unwired controls instead of faking success.
   - A unified API-client contract: `requestList` SHALL not return `[]` for unrecognized shapes; it SHALL throw a structured error with `code`/`requestId`/`status`/`fieldErrors`, backed by a consistent `PageResponse<T>` envelope.
   - Capability/feature-flag gating of stubbed backends (`ListingOpsServiceImpl` reprice-apply, `CustomerServiceImpl` ticket creation, `FinanceServiceImpl` payment creation) so non-functional actions are not shown.
   - Authentication token storage hardening (refresh token in HttpOnly/Secure/SameSite cookie, short-lived in-memory access token, cookie-based refresh + CSRF protection).
2. THE hosting spec SHALL depend on (and SHALL NOT re-specify) the following UX items, which SHALL be owned and implemented by `platform-ux-logistics-enhancements`:
   - Top-level navigation consolidation (Home, Advertising, Products, Supply Chain, Finance, Customer, System), the merged Workbench/To-Do Center, the merged Data Connections area, single connection wizard, Advertising-workspace consolidation, merged account area, responsive sidebar/mobile drawer, real-or-removed global search/notification controls, standardization on React Query/Sonner/AlertDialog, and decomposition of thousand-line pages.
3. WHERE this spec's own dashboard, settings, and approval UI are implemented, THEY SHALL conform to the conventions established by the above specs (the unified API client, the standard page pattern, and the consolidated navigation) rather than introducing parallel patterns.
4. THE hosting spec's design and tasks SHALL treat these prerequisites as inputs/assumptions and SHALL NOT duplicate their acceptance criteria.
