# Requirements Document

## Introduction

This feature is a structural rework of the **AdPilot AI** advertising module, spanning the React/TypeScript frontend (`frontend/`) and the Java 17 / Spring Boot 3.2.5 backend (`backend-java/`, MyBatis-Plus + JPA + MySQL 8.0 + Redis). It originates from a detailed review of the live advertising code (`backend-java/src/main/java/com/adpilot/modules/advertising/` and the advertising-facing frontend pages). The review found that the module behaves as a "local ad-data management prototype": writes report only success or failure, recommendations are logged-then-marked-applied without driving any platform action, several backend VO/DTO fields drift from what the frontend renders, data-logic defects distort recommendations and trends, tenant/store isolation is inconsistent across endpoints, and the eleven flat tabs lack a coherent information architecture.

The goal is to turn the advertising module into a coherent Amazon Ads management workflow with explicit operation state, scope, and platform-sync feedback, honest action semantics, correct API contracts, correct data logic, and consistent isolation and permission enforcement, building on the reusable table and data-fetching foundation partially delivered by `platform-ux-logistics-enhancements`.

### Current State, Prerequisite Milestone, and Phasing

This subsection grounds the spec in verified current reality so the requirements are not read as describing an already-complete Amazon auto-execution platform.

**Current state (verified against code).** Today the Advertising_Module is a **local ad-data management and local keyword-bid adjustment** system, NOT a full Amazon auto-execution platform. Specifically: there is **no** `PlatformWriteConnector` implementation; the only existing write path is `WriteBackService.apply(recommendationId)`, which is recommendation-only; the AI optimizer (`AiHostingOptimizer`) currently adjusts only **local keyword bids**; `riskPreference` exists only on `GoalEntity` (a Campaign has no personality field today); no advertising entity has an optimistic-lock version column; `MarketplaceEntity` has a currency but **no** timezone; the permission seed contains `advertising:view`, `advertising:manage`, `advertising:approve`, `keyword:view`, `keyword:manage`, and `keyword:apply` but **no** `advertising:execute`; and `parentAsin` and `targetingGoal` are non-persistent Campaign fields. Additionally, the current `CreateGoalPage` **auto-changes the risk preference** when an operator selects a goal type / targeting type, which conflicts with the explicit-confirmation rule of Requirement 49.8 (AI_Personality MUST NOT be switched silently when an Optimization_Goal is chosen); this auto-change behavior is **in-scope to fix** in this rework.

**Prerequisite milestone — Amazon Ads Write Connector.** The `PlatformWriteConnector` (the platform's `Write_Connector` implementation) is an explicit **PREREQUISITE MILESTONE** for all platform execution. The Amazon Ads Write_Connector implementation is registered ONCE for the platform (one connector implementation per platform), and a Store becomes write-capable when that implementation exists AND the Store has a valid active PlatformConnection. Until a Store is write-capable, the platform-dependent Sync_States (`submitted`, `amazon-processing`, `effective`, `cancel_requested`, `reconciliation_required`) and the platform callbacks that drive them are **NOT reachable**, and the system operates in **local-draft / simulation mode** only for that Store. Every requirement that depends on real platform execution states its dependency on this milestone (see Requirement 53).

**Net-new generalization.** The generic `Operation_Write_Back` (`applyOperation(operationId)`) defined in Requirement 2 is **NET-NEW** work that generalizes the current recommendation-only `apply(recommendationId)` path. It does not exist today.

**Phased delivery of executable AI hosting.** Executable AI hosting capabilities ship in phases that match the current optimizer's real reach (see Requirement 54): **V1 = bid adjustment only** (matches the current optimizer), **V2 = budget adjustment**, **V3 = keyword expansion + negative keywords**. Capabilities not yet implemented for the active phase MUST NOT be shown in the UI as executable.

### Scope boundaries and overlap with existing specs

This spec deliberately **reconciles with, and does not duplicate**, four existing specs. Where this spec changes a surface owned by another spec, the requirement notes the reconciliation explicitly, and the design phase MUST reconcile rather than re-define:

- **`core-platform-completion`** owns platform data sync, RBAC enforcement primitives (`Permission_Service` / `Data_Scope_Service`), scheduling, alerts, and the platform write-back contract (its Requirement 13.1/13.2: applying an AI recommendation submits the corresponding change to the live platform through the Store's Platform Connection, audits the platform response, and leaves the internal record unchanged on rejection). This spec **consumes and generalizes** that write-back path (see Requirement 2); it does not redefine sync, the connector contract, or approval-gated write-back. This spec DOES, however, depend on an agreed **extension** to that connector contract (adding `queryStatus`, `requestCancel`, `supportsCancel`, a platform-status → Sync_State mapping, callback signature verification, and platform-reference correlation) — captured as Requirement 55 — which is a required extension that does not exist today, not a redefinition of the connector ownership.
- **`app-functionality-completion`** owns the eleven-tab campaigns taxonomy and the Insight Agent (its Requirement 16/19/24). This spec **regroups the presentation** of those tabs into four navigational groups without removing or renaming the underlying tab data surfaces, and does not redefine the Insight Agent.
- **`platform-ux-logistics-enhancements`** delivered a **base** for the reusable `Shared_Data_Table` / `Filter_Toolbar` / `Bulk_Action_Bar`, the react-query `Data_Fetching_Layer`, and the `CampaignsWorkspace` decomposition. The base is only **partially** wired in the advertising surfaces: `total` and `tableKey` props exist but are not wired through to the advertising tables; there is no pagination control, no column-management entry, no export entry, and no saved-view entry rendered in the advertising UI. THIS spec **completes those missing capabilities** for the advertising tables (see Requirement 31) rather than rebuilding the base.
- **`project-fix-and-cleanup`** owns schema consolidation and the `db/schema.sql` single-source-of-truth/build/deploy approach. Any new persisted field this spec requires (for example the Operation and pending-change tables, the Outbox table, optimistic-lock version columns, idempotency keys, operation state, the campaign-level personality field, the Marketplace timezone field, and the campaign↔product/parent-ASIN association table) is added consistent with that approach and is **not** a schema-strategy change here.

Three API contracts that this spec depends on are **treated as preconditions to be confirmed at the start of implementation**, not as already-completed work. They are captured as verifiable acceptance criteria in Requirement 1 (Prerequisites / Preconditions): campaign update accepting `PATCH` with JSON `{dailyBudget}`, keyword update accepting `PATCH`, and generate-recommendations taking `storeId` as a query parameter and returning the generated count.

Acceptance criteria are written in EARS format and remain technology-neutral about implementation while aware of the existing stack.

## Glossary

- **System**: The AdPilot AI application as a whole (frontend + backend) unless a more specific component is named.
- **Frontend**: The React + TypeScript single-page application in `frontend/` that consumes the Backend under the `/api` prefix.
- **Backend**: The Spring Boot service in `backend-java/` exposing REST endpoints under `/api`.
- **Advertising_Module**: The Backend services and Frontend pages under the advertising domain (`modules/advertising`), covering campaigns, ad groups, product ads, targets/keywords, negatives, search terms, bid changes, budget, goals, recommendations, AI hosting, and smart diagnosis.
- **Active_Store**: The Store currently selected in the header store switcher, used to scope store-scoped advertising data. (Store and store scoping originate in `core-platform-completion`.)
- **Operation**: A single advertising write action initiated by an operator or by the Advertising_Module — for example: pause a campaign, change a budget, change a bid, add a keyword, add a negative keyword, an AI-hosting adjustment, apply a recommendation, or create a campaign. Every write that changes an advertising object is modeled as an Operation regardless of its source.
- **Operation_Write_Back**: The generic Backend capability that exposes `applyOperation(operationId)` and routes any `platform_mutation` Operation (pause, budget change, bid change, keyword add, negative add, AI-hosting adjustment, recommendation-sourced change) through the Active_Store's PlatformConnection using the platform's Write_Connector implementation, regardless of the Operation_Source. `local_configuration` Operations are NOT routed through Operation_Write_Back. A Recommendation is one source that produces an Operation; the recommendation-specific write-back path owned by `core-platform-completion` (its `apply(recommendationId)` / Requirement 13.1) is reconciled as a delegation to Operation_Write_Back.
- **Operation_Source**: The origin classification of an Operation, being exactly one of `manual`, `recommendation`, `one_click_optimize`, `ai_hosting`, or `creation`. Operation_Source is an IMMUTABLE audit fact recorded at creation and is never rewritten for the life of the Operation.
- **origin**: An IMMUTABLE audit fact on an advertising object recording how the object entered the system, being exactly one of `local` (created inside the Advertising_Module) or `amazon_import` (ingested from Amazon). origin is set once and never rewritten; it is distinct from Sync_State (the lifecycle) and from Operation_Source.
- **amazon_campaign_id**: The Amazon-assigned campaign identifier stored on a Campaign. A Campaign is included in the Amazon-synced campaign list only when it both has a non-empty amazon_campaign_id and has a creation Operation that reached the `effective` Sync_State. amazon_campaign_id is the field that gates synced-list inclusion.
- **Sync_State**: The explicit lifecycle status of an Operation with respect to the external platform, being exactly one of: `local-only` (persisted internally, no platform submission attempted or possible; a terminal local state), `pending` (created and awaiting submission), `awaiting_approval` (created but held because the change is greater than or equal to the approval threshold and needs operator approval before submission), `submitted` (sent to the platform, awaiting acknowledgement), `amazon-processing` (acknowledged by the platform and being applied there), `effective` (confirmed applied on the platform), `failed` (platform rejected or submission errored), `cancel_requested` (a cancellation has been requested for an already-submitted or in-flight Operation, where a local cancel does NOT guarantee the platform stops and the final platform state is still being resolved), `cancelled` (confirmed not applied on the platform — the Operation never took effect), `superseded` (replaced by a newer Operation, for example after a personality switch), `expired` (an in-flight Operation that exceeded its platform timeout; NOT terminal, because the platform may still return a final result), or `reconciliation_required` (the platform's actual state must be reconciled before any further action such as a retry). The states `submitted`, `amazon-processing`, `effective`, `cancel_requested`, and `reconciliation_required` are reachable only after the Write_Connector prerequisite milestone (Requirement 53) is met for the Store; `awaiting_approval`, `cancelled`, and `superseded` are local-tracked lifecycle states.
- **operationScope**: The execution-scope classification of an Operation, being exactly one of `platform_mutation` (a change that mutates platform-side state and must be submitted to Amazon) or `local_configuration` (a change to internal-only configuration that is never submitted to Amazon — for example AI personality, Goal configuration, the per-store hosting policy/strategy, and notification configuration). A Saved_View change is NOT modeled as an Operation at all (it creates no Operation_Record and no operation-log entry; see Requirement 3). Only `platform_mutation` Operations create an Outbox entry, carry platform Sync_States, and route through Operation_Write_Back and the Write_Connector. `local_configuration` Operations never create an Outbox entry, are never submitted to Amazon, and never carry platform Sync_States; instead a `local_configuration` Operation carries an executionStatus (`applied`, `failed`, or `cancelled`).
- **Outbox**: A persisted table of pending platform submissions written inside the same database transaction as a `platform_mutation` Operation's records. An asynchronous Outbox worker reads Outbox entries and submits them to the platform through the Write_Connector outside any database transaction, so that no external Amazon request is ever performed inside a database transaction. A `local_configuration` Operation does NOT create an Outbox entry.
- **Pending_Overlay**: A query-layer overlay that joins the entity's Amazon-confirmed value with any unfinished Operation's pending value, so the UI can present the confirmed value alongside the pending change without duplicating every writable field into two physical columns.
- **logicalOperationId**: A stable identifier shared by every attempt of one logical change. All retries of the same logical Operation carry the same logicalOperationId, so the full attempt history of a single logical change can be grouped and audited.
- **logicalIdempotencyKey**: A stable key that dedupes repeated operator activations (coalesces repeated clicks) for the SAME logical change. Two requests carrying the same logicalIdempotencyKey are treated as the same logical Operation and are coalesced rather than producing two logical Operations.
- **attemptId**: A unique identifier assigned to each individual attempt of a logical Operation. A retry creates a NEW attempt with a new attemptId under the same logicalOperationId / logicalIdempotencyKey.
- **submissionIdempotencyKey**: A unique key per platform submission attempt, sent to the platform so the platform can dedupe that specific submission. Because each attempt carries its OWN submissionIdempotencyKey, a retry is NOT blocked or deduped by a prior attempt's key — only an exact re-delivery of the SAME submission is deduped.
- **attemptNumber**: The 1-based ordinal of an attempt within its logicalOperationId (the first attempt is 1, the first retry is 2, and so on), used to enforce the retry limit.
- **Idempotency_Key**: The umbrella concept for the key model above. Click coalescing (duplicate-suppression of repeated operator activations) is performed using the logicalIdempotencyKey; platform-submission deduplication is performed using the submissionIdempotencyKey. The two concerns are kept distinct so that a retry (new submissionIdempotencyKey) is never blocked by the prior attempt's submission key while repeated clicks (same logicalIdempotencyKey) are still coalesced.
- **Write_Back**: The act of submitting an Operation's change to the live platform through the Active_Store's PlatformConnection using the platform's Write_Connector implementation. Performed by Operation_Write_Back, building on the `core-platform-completion` write-back contract.
- **PlatformConnection**: The per-Store credential/connection record that authorizes the System to act against the platform on that Store's behalf (owned by `core-platform-completion`). A Store is write-capable when it has a valid, active PlatformConnection.
- **Write_Connector**: The platform write connector IMPLEMENTATION (contract delivered by `core-platform-completion`). The Amazon Ads Write_Connector implementation is registered ONCE for the PLATFORM (one connector implementation per platform), NOT one per Store. Platform-execution availability for a given Store therefore means BOTH that the platform's Write_Connector implementation exists AND that the Store has a valid active PlatformConnection. A Store is write-capable only when both conditions hold. Throughout this document, the shorthand "the Store has a Write_Connector" / "the Store has no Write_Connector" means the Store IS / IS NOT write-capable in this sense (platform Write_Connector implementation present AND a valid active PlatformConnection for the Store), not that a separate connector object is registered per Store.
- **platformValue**: The CONCEPTUAL "Amazon-confirmed value" of a writable advertising field — the last platform-confirmed (Amazon actual) value the UI presents as "Amazon truth". It is stored once on the entity table (not duplicated per field) and changes only when an Operation becomes `effective`. platformValue is a concept, not a mandate to add a second physical column per field.
- **pendingValue**: The CONCEPTUAL "pending change" of a writable advertising field awaiting sync, materialized through the Pending_Overlay from the unfinished Operation's after value rather than from a duplicate physical column on the entity. While an Operation is in any Unsettled_State (`pending`, `awaiting_approval`, `submitted`, `amazon-processing`, `cancel_requested`, `expired`, or `reconciliation_required`), the requested new value lives in the Operation record and is surfaced as the field's pendingValue; the entity's confirmed value is unchanged. On `effective` the Operation's after value becomes the entity's confirmed value; on `failed` the failed Operation is retained and the confirmed value is unchanged.
- **Unsettled_State**: The set of Sync_States in which an Operation's outcome on the platform is not yet finally settled, defined as EXACTLY `pending`, `awaiting_approval`, `submitted`, `amazon-processing`, `cancel_requested`, `expired`, and `reconciliation_required`. For an Operation in any Unsettled_State, the Advertising_Module surfaces the Operation's pending value through the Pending_Overlay (Requirement 7) and the in-flight conflict lock (Requirement 5) blocks a new conflicting Operation against the same object. The settled states are `local-only`, `effective`, `failed`, `cancelled`, and `superseded`.
- **Operation_Record**: The persisted, auditable record of an Operation, capturing the Operation_Source, the operationScope, the logicalOperationId, the logicalIdempotencyKey, the attemptId, the submissionIdempotencyKey, the attemptNumber, the parentOperationId (for a publish Operation, referencing the original terminal `local-only` draft Operation it publishes; null otherwise), the before value (the Amazon-confirmed value at creation), the after value (the requested pending value), the `reversible` flag (whether the Operation can be undone by a compensating Operation), the count of affected objects, the acting user, the timestamps (created and last-updated), the resulting Sync_State, the executionStatus (for a `local_configuration` Operation only, being exactly one of `applied`, `failed`, or `cancelled`; null for a `platform_mutation` Operation), the platform result (where available), and the statusReason (the human-readable reason for the current state, recorded for `failed`, `cancelled`, `expired`, `cancel_requested`, and `reconciliation_required`).
- **Recommendation**: An AI-generated optimization suggestion (for example: adjust bid, add keyword, add negative keyword, change budget) produced by the recommendation engine and surfaced for operator action. A Recommendation is one Operation_Source.
- **Recommendation_Type**: The category of a Recommendation that determines how applying it maps to an Operation.
- **Recommendation_Status**: The lifecycle state of a Recommendation, being exactly one of `pending`, `applying`, `effective`, `failed`, `dismissed`, `watching`, or `local-only`.
- **Harvest_Action**: The action chosen when harvesting a Search_Term, being exactly one of `add_exact`, `add_phrase`, `add_negative`, or `watchlist`.
- **Search_Term**: A customer search query recorded against a campaign and ad group with performance metrics, eligible for harvesting.
- **Keyword**: A targeting term (target) belonging to an ad group with a match type and performance metrics.
- **Negative_Keyword**: A term that suppresses targeting for a campaign or ad group.
- **Ad_Group**: A grouping of targets and product ads within a Campaign.
- **Campaign**: An Amazon advertising campaign belonging to a Store.
- **Object_Status**: The lifecycle state of a Campaign, Keyword, or Target, expressed with the single canonical vocabulary `enabled | paused | archived`.
- **ACoS**: Advertising Cost of Sales — advertising spend divided by advertising sales — stored as a decimal ratio (for example `0.1492` denotes 14.92%) and multiplied by 100 only for display.
- **Goal**: An advertising objective record surfaced through `GoalVo`, associating campaigns and products with target metrics and trend data.
- **AI_Hosting**: Placing a Campaign under the Advertising_Module's internal AI optimization (AI托管), which proposes and applies adjustments within configured guardrails; distinct from platform-side eligibility or platform application. The former term "入格" is retired (see Requirement 48) because it implied Amazon official eligibility.
- **AI_Hosting_Status**: The AI hosting state of a Campaign. The Backend contract represents it as a STABLE MACHINE value, being exactly one of `hosted` or `not_hosted`; the Frontend translates these to the display copy 托管中 / 未托管 (AI托管状态). Replaces the retired "入格 / 已入格" vocabulary.
- **AI_Personality**: The enforceable optimizer policy profile that governs how aggressively and how riskily the AI optimizer acts to reach the Optimization_Goal. The Backend contract represents it as a STABLE MACHINE value, being exactly one of `conservative`, `balanced`, or `aggressive`; the Frontend translates these to the FIXED display names 常规型 (conservative), 平衡型 (balanced), and 激进型 (aggressive). AI_Personality is the UI term 人格 (formerly 风险偏好). It is distinct from Optimization_Goal (what the AI aims for) and Safety_Boundary (hard limits the AI can never cross). AI_Personality is an executable policy, not merely a stored label. A Campaign's effective personality is RESOLVED per Requirement 49 (Campaign override > Goal default > Store default > `balanced`).
- **Optimization_Goal**: The objective the AI optimizer aims for, surfaced as 优化目标 (formerly 托管目标). The Backend contract represents it as a STABLE MACHINE enum, being exactly one of `profit_first`, `sales_growth`, `rank`, or `clearance`; the Frontend translates these to display copy.
- **Machine_Value_Enum**: A stable, language-neutral backend enumeration value (for example AI_Hosting_Status `hosted`/`not_hosted`, AI_Personality `conservative`/`balanced`/`aggressive`, Optimization_Goal `profit_first`/`sales_growth`/`rank`/`clearance`, Object_Status `enabled`/`paused`/`archived`). The Backend contract returns Machine_Value_Enum values only and never Chinese display strings; the Frontend is solely responsible for translating Machine_Value_Enum values to display copy.
- **Safety_Boundary**: The set of hard limits, surfaced as 安全边界, that the AI optimizer can never cross regardless of AI_Personality, including maxBid, maxCpc, maximum daily budget increase, approval rules, and platform sync state. The Safety_Boundary always takes precedence over any AI_Personality-allowed magnitude. The effective Safety_Boundary for a Campaign is RESOLVED through a configuration hierarchy with the precedence Campaign override > Goal boundary > Store policy > System default (see Requirements 22 and 49): the first level that defines a given limit wins, and a Campaign-level override always takes precedence over a Goal, Store, or System value.
- **Personality_Policy**: The per-personality, configurable backend control table that maps each AI_Personality value to CONCRETE NUMERIC control fields: `minClicks`, `minOrders`, `lookbackDays`, `minConversionRate`, `negativeConfidenceThreshold`, `acosToleranceRatio`, `approvalBidChangeRatio`, `approvalBudgetChangeRatio`, `maxBidIncreaseRatio`, `maxBidDecreaseRatio`, `maxDailyBudgetIncreaseRatio`, `adjustmentCooldownHours`, `exploreBudgetRatioMin`, and `exploreBudgetRatioMax`. All values are configurable backend values, never hardcoded in the Frontend and never expressed as vague qualitative levels.
- **Campaign_Personality**: A NEW optional campaign-level AI_Personality override field. When set, it overrides the Goal-level default for that Campaign; when unset, the Campaign inherits the Goal default. See the resolution rule in Requirement 49.
- **Goal_Personality_Default**: The default AI_Personality stored on a Goal (the existing `risk_preference` field on `GoalEntity`), used for every Campaign associated with the Goal that does not set a Campaign_Personality override.
- **Store_Default_Personality**: A NEW Store-level default AI_Personality, used for a Campaign whose effective personality cannot be resolved from a Campaign_Personality override or a Goal_Personality_Default (for example a Campaign whose `goal_id` is null). It is the next-to-last level of the personality resolution chain before the final `balanced` system fallback (see Requirement 49).
- **Personality_Policy keyword/negative fields**: The V3 keyword-policy fields of the Personality_Policy table: `keywordExpansionMode` (one of `off`, `suggest`, or `auto`), `negativeKeywordMode` (one of `suggest`, `approval`, or `auto`), `keywordConfidenceThreshold`, `maxKeywordsAddedPerDay`, and `maxNegativesAddedPerDay`. These govern the V3 keyword-expansion and negative-keyword capabilities (Requirement 54) and are configurable backend values per personality.
- **parentOperationId**: A field on a NEW publish Operation_Record that references the original terminal `local-only` draft Operation the publish Operation publishes, so the draft and its later platform publish can be correlated and audited without transitioning the terminal draft. The publish Operation keeps its OWN new `logicalOperationId`; the link to the draft is carried solely by `parentOperationId` (this replaces the former "shared logicalOperationId or Operation_Link" approach). `parentOperationId` is null for an Operation that does not publish a `local-only` draft.
- **Personality_Rule_Version**: The version identifier of the Personality_Policy rule-set in effect when an AI decision is made, recorded on the Operation_Record for each AI decision.
- **Smart_Diagnosis**: A per-product (parent ASIN) diagnosis task that analyzes ad structure and produces a diagnosis result.
- **Context_Bar**: A persistent header region in the Advertising_Module showing the active store, site, advertising account, currency, data date, and last-sync time.
- **Filter_Chip**: A removable visual indicator of one currently applied filter condition.
- **KPI_Panel**: The collapsible region presenting key advertising metrics and a trend chart with a selectable date range and a comparison period.
- **Saved_View**: A named, persisted combination of column configuration, filters, and sort order scoped to a user and a table key, whose base is delivered by `platform-ux-logistics-enhancements` and surfaced for the advertising tables here.
- **Table_Toolbar**: The control strip above an advertising table exposing the result count, bulk operations, new-record, refresh, export, and column-management controls.
- **Bulk_Operation**: An Operation applied to a set of selected rows in a single user gesture.
- **Permission_Service**: The Backend authorization component (owned by `core-platform-completion`) that verifies a caller holds a required permission.
- **Data_Scope_Service**: The Backend component (owned by `core-platform-completion`) that restricts records to the caller's effective data scope, including per-Store ownership.
- **Marketplace_Timezone**: A NEW timezone field added to the Marketplace data (the `MarketplaceEntity`, which today has a currency but no timezone). It records the IANA timezone of the Active_Store's Amazon marketplace and is the single source used to compute "today", day boundaries, date ranges, and scheduling for advertising metrics. Requirements that reference day boundaries or "today" depend on this new field (see Requirement 51).

## Requirements

### Requirement 1: Prerequisites and Preconditions

**User Story:** As an implementer, I want the front/back API contracts this rework depends on to be confirmed by verifiable tests at the start of implementation, so that the rework is not built on assumed-fixed behavior.

Reconciliation: these contracts touch endpoints owned across the advertising backend and `app-functionality-completion`. They are stated here as preconditions to confirm, not as completed work.

#### Acceptance Criteria

1. THE Backend SHALL accept a campaign update issued as an HTTP `PATCH` request whose JSON body contains a `dailyBudget` field and SHALL apply the supplied `dailyBudget` to the targeted Campaign.
2. WHEN a contract test exercises the campaign update endpoint, THE contract test SHALL confirm that the Frontend request shape (`PATCH` with JSON `{dailyBudget}`) matches the Backend's accepted contract.
3. THE Backend SHALL accept a keyword update issued as an HTTP `PATCH` request and SHALL apply the supplied fields to the targeted Keyword.
4. WHEN a contract test exercises the keyword update endpoint, THE contract test SHALL confirm that the Frontend `PATCH` request shape matches the Backend's accepted contract.
5. THE Backend SHALL accept a generate-recommendations request that supplies `storeId` as a query parameter and SHALL return the count of Recommendations generated.
6. WHEN a contract test exercises the generate-recommendations endpoint, THE contract test SHALL confirm that the Frontend request shape (`storeId` query parameter) matches the Backend's accepted contract and that the returned generated count is present.
7. IF any of the three contract tests fails at the start of implementation, THEN THE implementation SHALL treat the corresponding contract as an open precondition to be fixed before the dependent requirement is built rather than assuming the contract holds.

### Requirement 2: Generic Operation Write-Back

**User Story:** As an advertising operator, I want every advertising write — whatever its source — to route through one generic write-back path, so that pause, budget, bid, keyword, negative, hosting, and recommendation changes all behave consistently against the platform.

Reconciliation: `core-platform-completion` Requirement 13.1 defines applying a recommendation as submitting the change through the Store's Platform Connection. This requirement generalizes that into Operation_Write_Back and reconciles recommendation-sourced changes as a delegation to the generic path.

#### Acceptance Criteria

1. THE Advertising_Module SHALL classify every Operation with an operationScope of `platform_mutation` or `local_configuration`, and SHALL expose an Operation_Write_Back capability that accepts `applyOperation(operationId)` and routes the identified Operation through the Active_Store's PlatformConnection using the platform's Write_Connector implementation regardless of the Operation_Source, ONLY for `platform_mutation` Operations.
2. WHEN a `platform_mutation` Operation is produced from any source (manual pause, budget change, bid change, keyword add, negative add, AI-hosting adjustment, or a recommendation), THE Advertising_Module SHALL submit that Operation through Operation_Write_Back.
3. WHEN a recommendation-sourced `platform_mutation` Operation is submitted, THE Advertising_Module SHALL route it through Operation_Write_Back rather than through a separate recommendation-only write path, so that the `core-platform-completion` recommendation write-back delegates to the generic path.
4. WHERE the Active_Store is not write-capable (no platform Write_Connector implementation or no valid active PlatformConnection), THE Operation_Write_Back capability SHALL NOT attempt a platform submission and SHALL resolve the `platform_mutation` Operation to the `local-only` Sync_State.
5. WHEN Operation_Write_Back submits an Operation, THE Advertising_Module SHALL record the platform result on the Operation_Record per Requirement 8.
6. THE Advertising_Module SHALL NOT route a `local_configuration` Operation through Operation_Write_Back and SHALL NOT submit it to the platform.

### Requirement 3: Explicit Operation Sync State

**User Story:** As an advertising operator, I want every write to surface an explicit platform-sync state instead of a bare success or failure, so that I know whether a change is only saved locally, in flight to Amazon, or actually effective.

#### Acceptance Criteria

1. WHEN an operator or the Advertising_Module initiates a `platform_mutation` Operation, THE Advertising_Module SHALL assign the Operation a Sync_State drawn from the defined set (`local-only`, `pending`, `awaiting_approval`, `submitted`, `amazon-processing`, `effective`, `failed`, `cancel_requested`, `cancelled`, `superseded`, `expired`, `reconciliation_required`).
2. WHERE the Active_Store is not write-capable, THE Advertising_Module SHALL assign the `platform_mutation` Operation the Sync_State `local-only` and SHALL persist the Operation's effect internally as a pending change surfaced through the Pending_Overlay per Requirement 7.
3. WHERE the Active_Store is write-capable, THE Advertising_Module SHALL submit the `platform_mutation` Operation through Operation_Write_Back and SHALL set the Operation's Sync_State to the state reported by that path.
4. WHEN Operation_Write_Back reports that a submitted Operation has been confirmed on the platform, THE Advertising_Module SHALL set the Operation's Sync_State to `effective`.
5. IF Operation_Write_Back reports that an Operation was rejected or its submission errored, THEN THE Advertising_Module SHALL set the Operation's Sync_State to `failed` and SHALL leave the affected record's Amazon-confirmed value unchanged.
6. WHEN the Frontend displays an Operation, THE Frontend SHALL display the Operation's current Sync_State using a distinct label for each state.
7. THE Advertising_Module SHALL NOT assign a platform Sync_State (`submitted`, `amazon-processing`, `effective`, `cancel_requested`, `reconciliation_required`) to a `local_configuration` Operation, and SHALL NOT create an Outbox entry for, or submit to Amazon, any `local_configuration` Operation.
8. WHEN the Advertising_Module records a `local_configuration` Operation (AI personality, Goal configuration, the per-store hosting policy/strategy, or notification configuration), THE Advertising_Module SHALL assign it an `executionStatus` drawn from exactly the values `applied`, `failed`, or `cancelled` instead of a platform Sync_State, and THE Frontend SHALL display the Operation's `executionStatus` rather than forcing a platform Sync_State onto the local configuration change.
9. A Saved_View change SHALL NOT create an advertising Operation: THE Advertising_Module SHALL NOT create an Operation_Record and SHALL NOT write an operation-log entry for a Saved_View change, so that personal view configuration never pollutes the operation log; AI personality, Goal configuration, hosting policy/strategy, and notification configuration changes REMAIN `local_configuration` Operations (carrying an `executionStatus` per criterion 8), but a Saved_View change is explicitly EXCLUDED from creating an Operation.

### Requirement 4: Operation State Machine and Timeouts

**User Story:** As an advertising operator, I want operations to follow a defined state machine with timeouts and retries, so that an operation cannot get stuck in an inconsistent state and a retry does not corrupt the original attempt.

#### Acceptance Criteria

1. THE Advertising_Module SHALL permit exactly the following Sync_State transitions for an Operation and SHALL reject any other transition:
   - `pending` → `awaiting_approval` (the change's absolute change ratio is greater than or equal to the approval threshold per the Personality_Policy or Safety_Boundary);
   - `pending` → `submitted` (no approval required and the Store is write-capable);
   - `pending` → `cancelled` (explicit operator cancel of a not-yet-submitted Operation) | `superseded` (replaced by a newer Operation);
   - `awaiting_approval` → `submitted` (approved) | `cancelled` (rejected or explicit operator cancel of a not-yet-submitted Operation) | `superseded`;
   - `submitted` → `amazon-processing` | `effective` (the platform may confirm directly without passing through `amazon-processing`) | `failed` | `expired` | `cancel_requested` (operator requests cancellation of an already-submitted Operation);
   - `amazon-processing` → `effective` | `failed` | `expired` | `cancel_requested` (operator requests cancellation of an in-flight Operation);
   - `cancel_requested` → `cancelled` (the platform confirmed the change was not applied) | `effective` (the platform reports the change was already applied; undoing it requires a compensating rollback Operation) | `reconciliation_required` (the cancellation request itself errored or could not be confirmed — the system continues querying the platform's actual state rather than marking the original ad Operation `failed`, because a failed cancellation does NOT mean the original ad operation failed);
   - `reconciliation_required` → `effective` (the reconciled platform state shows the change is applied) | `failed` (the reconciled platform state shows the change did not apply or errored) | `cancelled` (the reconciled platform state shows the change was not applied and the Operation never took effect);
   - `expired` → `effective` (the platform returned success after the timeout) | `failed` (the platform returned failure after the timeout) | `reconciliation_required` (the platform's actual state must be reconciled before any retry);
   - a retry path from `failed` that creates a NEW `pending` attempt;
   - `local-only` is a terminal local state with no outgoing transitions.
2. WHEN an operator retries a `failed` Operation, THE Advertising_Module SHALL create a NEW Operation attempt in the `pending` state with a new `attemptId`, a new `submissionIdempotencyKey`, and an incremented `attemptNumber` under the same `logicalOperationId` and `logicalIdempotencyKey`, rather than mutating the original `failed` Operation_Record.
3. WHILE an Operation is in `submitted` or `amazon-processing`, THE Advertising_Module SHALL poll or accept a platform callback to advance the Operation, using the platform acknowledgement as the callback source.
4. IF an Operation remains in `submitted` or `amazon-processing` beyond a defined timeout duration of 15 minutes, THEN THE Advertising_Module SHALL transition the Operation to `expired` with a timeout reason and SHALL leave the affected record's confirmed value unchanged.
5. THE Advertising_Module SHALL limit automatic retries of a single logical Operation to a maximum of 3 attempts (tracked by `attemptNumber` under one `logicalOperationId`) and SHALL require an explicit operator retry beyond that limit.
6. WHEN an Operation's Sync_State changes, THE Advertising_Module SHALL update the Operation_Record's last-updated timestamp.
7. WHEN an operator explicitly cancels an Operation that is in `pending` or `awaiting_approval` (not yet submitted), THE Advertising_Module SHALL transition the Operation DIRECTLY to `cancelled` and SHALL leave the affected record's confirmed value unchanged.
8. WHEN an operator requests cancellation of an Operation that is in `submitted` or `amazon-processing` (already submitted or in flight), THE Advertising_Module SHALL transition the Operation to `cancel_requested` rather than directly to `cancelled`, because a local cancel does NOT guarantee the platform stops.
9. WHILE an Operation is in `cancel_requested`, THE Advertising_Module SHALL request cancellation from the platform and SHALL poll the platform for the final state, and: WHERE the platform supports cancel and confirms the change was not applied, THE Advertising_Module SHALL transition the Operation to `cancelled`; WHERE the platform does not support cancel, THE Advertising_Module SHALL continue polling to resolve the final platform state rather than flipping the local state; IF the platform reports the change has already become `effective`, THEN THE Advertising_Module SHALL transition the Operation to `effective` and SHALL achieve the cancellation by creating a compensating rollback Operation per Requirement 8 rather than by a local state flip; and IF the cancellation request itself errors or cannot be confirmed, THEN THE Advertising_Module SHALL transition the Operation to `reconciliation_required` and SHALL continue querying the platform's actual state, and SHALL NOT mark the original ad Operation `failed`, because a failed cancellation does not mean the original ad operation failed (the platform may have already executed it).
10. WHEN an Operation expires, THE Advertising_Module SHALL query the platform's actual state BEFORE attempting any retry and SHALL NOT immediately retry; WHERE the platform reports the change applied, THE Advertising_Module SHALL transition the expired Operation to `effective`; WHERE the platform reports the change did not apply or errored, THE Advertising_Module SHALL transition it to `failed`; and WHERE the platform's actual state cannot yet be determined, THE Advertising_Module SHALL transition it to `reconciliation_required` and SHALL hold further action until the platform state is reconciled.
11. WHEN a newer Operation replaces an in-flight Operation against the same object (for example after an AI_Personality switch per Requirement 49), THE Advertising_Module SHALL, WHERE the replaced Operation is not yet submitted (`pending` or `awaiting_approval`), transition it DIRECTLY to `superseded`, and WHERE the replaced Operation is already submitted or in flight (`submitted` or `amazon-processing`), route it through the `cancel_requested` path of criteria 8 and 9 rather than transitioning it directly to `superseded`; in both cases the affected record's confirmed value SHALL be left unchanged.
12. WHERE the Active_Store is not write-capable (the prerequisite milestone of Requirement 53 is not met), THE Advertising_Module SHALL NOT transition any Operation into `submitted`, `amazon-processing`, `effective`, `cancel_requested`, or `reconciliation_required` and SHALL keep the Operation in a local-tracked state (`local-only`, `pending`, `awaiting_approval`, `cancelled`, or `superseded`).

### Requirement 5: Operation Idempotency and Concurrency

**User Story:** As an advertising operator, I want operations to be idempotent and concurrency-safe, so that repeated clicks, retries, and platform callbacks do not create duplicate platform requests or overwrite another user's change.

#### Acceptance Criteria

1. WHEN an Operation is created, THE Advertising_Module SHALL assign it a `logicalOperationId` that is stable across all attempts of the logical change, a `logicalIdempotencyKey` that dedupes repeated operator activations for the same logical change, an `attemptId` unique to the attempt, a `submissionIdempotencyKey` unique to the platform submission attempt, and an `attemptNumber`.
2. WHEN the Advertising_Module receives a platform callback, a poll result, or a re-delivery that carries a `submissionIdempotencyKey` already processed, THE Advertising_Module SHALL recognize the duplicate submission and SHALL NOT produce a duplicate platform request; a retry, because it carries a NEW `submissionIdempotencyKey`, SHALL NOT be blocked by a prior attempt's `submissionIdempotencyKey`.
3. WHEN an operator activates the same Operation control repeatedly before the logical Operation completes, THE Advertising_Module SHALL coalesce the activations into a single logical Operation by `logicalIdempotencyKey`.
4. THE Advertising_Module SHALL add an optimistic-lock version column (a MyBatis-Plus `@Version` field) to the writable advertising entities that Operations mutate — at minimum Campaign, Goal, Keyword, Ad_Group, Target, Negative_Keyword, and Product_Ad — and WHEN an operator submits an Operation against such an object, THE Advertising_Module SHALL include the version the operator's view was loaded with and SHALL reject the Operation if the object's version has changed since.
5. WHEN the Backend applies an Operation's entity update, THE Backend SHALL use the MyBatis-Plus optimistic-lock update condition `WHERE id = ? AND version = ?` with `SET ..., version = version + 1`, and SHALL treat a zero-row update result as a version conflict that rejects the Operation.
6. IF an advertising object already has an Operation in any Unsettled_State (`pending`, `awaiting_approval`, `submitted`, `amazon-processing`, `cancel_requested`, `expired`, or `reconciliation_required`), THEN THE Advertising_Module SHALL reject a new conflicting Operation against the same object and SHALL inform the operator that an Operation is already in progress.
7. WHEN platform callbacks, polling, or retries are processed, THE Advertising_Module SHALL apply them idempotently so that processing the same platform result more than once produces the same final state.
8. WHERE an Operation becomes `effective` on the platform but the local object's optimistic-lock version changed during the external execution (a newer local change to the same object occurred while the Operation was in flight), THE Advertising_Module SHALL NOT silently overwrite the local object with the platform-confirmed value (no last-writer-wins); instead THE Advertising_Module SHALL enter a reconciliation that records BOTH the platform-confirmed value and the conflicting local change and SHALL require explicit operator resolution (merge/reconcile) before the confirmed value is updated.

### Requirement 6: Transaction and Batch Atomicity

**User Story:** As an advertising operator, I want each operation persisted atomically through an outbox and submitted to Amazon outside the transaction, so that partial writes never occur and a slow or failing Amazon call never holds a database transaction open.

#### Acceptance Criteria

1. WHEN the Advertising_Module records a `platform_mutation` Operation, THE Backend SHALL, within ONE database transaction, write the Operation_Record, the pending-change record, the audit log entry, and an Outbox entry, so that either all four persist or none persist; this first transaction SHALL NOT change the affected entity's Amazon-confirmed value and SHALL NOT call Amazon.
2. WHEN the Advertising_Module records a `local_configuration` Operation, THE Backend SHALL persist the Operation_Record and the audit log entry but SHALL NOT write an Outbox entry, SHALL NOT submit the change to Amazon, and SHALL NOT assign it a platform Sync_State.
3. THE Backend SHALL NOT perform any external Amazon (platform) request inside a database transaction; an asynchronous Outbox worker SHALL read Outbox entries and submit them to the platform through the Write_Connector outside any database transaction.
4. WHEN the platform confirms an Operation as effective, THE Backend SHALL update the affected entity's Amazon-confirmed value, and ON failure THE Backend SHALL retain the failed Operation and SHALL leave the entity's Amazon-confirmed value unchanged.
5. IF a Bulk_Operation references records belonging to more than one Store, THEN THE Backend SHALL reject the WHOLE batch rather than silently excluding the cross-store records.
6. WHEN a Bulk_Operation references records all within the caller's effective data scope, THE Backend SHALL process the batch with partial-success semantics, attempting each item independently and returning a per-item result.
7. WHEN a Bulk_Operation completes its API call, THE Backend SHALL return a uniform per-item result format identifying, for each referenced record, the record identifier, the **creation result** (whether an Operation was created successfully), and the failure reason where creation failed.
8. THE Backend SHALL distinguish three separate outcomes for a `platform_mutation` Operation and SHALL NOT conflate them: the **creation result** (the Operation was created and persisted successfully), the **submission result** (the Operation was handed to the platform through Operation_Write_Back), and the **platform final result** (the Operation became `effective` or `failed` on the platform); the per-item result returned when a Bulk_Operation's API call completes SHALL represent the creation result, and SHALL make clear that API completion means Operations were created, not that Amazon has applied them.

### Requirement 7: Confirmed Value and Pending Overlay Model

**User Story:** As an advertising operator, I want the page to show the Amazon-confirmed value alongside any pending change without the data model duplicating every writable field, so that a failed sync never corrupts the confirmed value and the schema stays simple.

#### Acceptance Criteria

1. THE Advertising_Module SHALL store, on the entity table, ONLY the Amazon-confirmed value of each writable advertising field, and SHALL NOT add a second physical column per field for the pending value.
2. THE Advertising_Module SHALL store the before value, the after value, and the pending value of a change on the Operation (and pending-change) record rather than on the entity.
3. WHEN the query layer returns a writable advertising field that has an Operation in any Unsettled_State (`pending`, `awaiting_approval`, `submitted`, `amazon-processing`, `cancel_requested`, `expired`, or `reconciliation_required`), THE Advertising_Module SHALL produce a Pending_Overlay that exposes both the entity's Amazon-confirmed value and the Operation's pending value, so that the pending value continues to be surfaced for every Unsettled_State.
4. WHEN an Operation against a field becomes `effective`, THE Advertising_Module SHALL update the entity's Amazon-confirmed value to the Operation's after value.
5. IF an Operation against a field becomes `failed`, `cancelled`, `superseded`, `expired`, or `reconciliation_required`, THEN THE Advertising_Module SHALL leave the entity's Amazon-confirmed value unchanged and SHALL retain the Operation record for audit (noting that `expired` and `reconciliation_required` are not terminal and the value is updated only if the Operation later reaches `effective`).
6. WHEN the Frontend renders a writable advertising field, THE Frontend SHALL display the Amazon-confirmed value and SHALL additionally display the pending value from the Pending_Overlay whenever a pending value is present and differs from the confirmed value.
7. WHEN no Operation is in progress for a field, THE Frontend SHALL read and display the entity's Amazon-confirmed value as the current value.
8. WHEN an operator recovers from a failed Operation by retrying or discarding, THE Advertising_Module SHALL either create a new Operation from the failed pending value (retry) or close the failed pending change (discard), leaving the Amazon-confirmed value unchanged in both cases.

### Requirement 8: Operation Record Detail and Remediation

**User Story:** As an advertising operator, I want each operation to record its before/after values, scope, actor, time, platform result, and failure reason with retry and undo, so that I can audit and recover from changes.

#### Acceptance Criteria

1. WHEN an Operation is recorded, THE Advertising_Module SHALL persist an Operation_Record containing the Operation_Source, the operationScope, the `logicalOperationId`, the `logicalIdempotencyKey`, the `attemptId`, the `submissionIdempotencyKey`, the `attemptNumber`, the `parentOperationId` where the Operation publishes a `local-only` draft, the before value, the after value, the `reversible` flag, the count of affected objects, the acting user identity, the created and last-updated timestamps, the resulting Sync_State, the `executionStatus` where the operationScope is `local_configuration`, the platform result where Operation_Write_Back provided one, and the `statusReason` (the human-readable reason for the current state), recorded for the states `failed`, `cancelled`, `expired`, `cancel_requested`, and `reconciliation_required` rather than only for `failed`.
2. WHEN an operator opens an Operation_Record, THE Frontend SHALL display the source, the before value, the after value, the affected-object count, the acting user, the operation time, the Sync_State, the platform result, and the `statusReason` where present (covering `failed`, `cancelled`, `expired`, `cancel_requested`, and `reconciliation_required`).
3. WHERE an Operation_Record has the Sync_State `failed`, THE Frontend SHALL present a retry control that creates a new Operation attempt through Operation_Write_Back when activated, per Requirement 4.
4. WHERE an Operation_Record has the Sync_State `effective` AND its `reversible` flag is `true` AND its before value is still valid (the field has NOT since been changed by a newer `effective` Operation), THE Frontend SHALL present an undo control that submits a compensating Operation restoring the before value when activated; WHERE the before value is no longer valid (a newer `effective` Operation has since changed the field) OR the `reversible` flag is `false` OR the Operation is not `effective`, THE Frontend SHALL NOT offer the undo control.
5. WHEN an operator activates the undo control for an Operation_Record, THE Advertising_Module SHALL record the compensating action as a new Operation_Record rather than deleting the original Operation_Record.

### Requirement 9: Recommendations, One-Click Optimize, and Hosting Drive Write-Back

**User Story:** As an advertising operator, I want "apply suggestion", the AI notification "one-click optimize", and AI hosting to actually drive the platform write path or honestly show "saved locally", so that I am not misled by changes that were only logged.

The review found these paths log the change and mark it applied without invoking Write_Back.

#### Acceptance Criteria

1. WHEN an operator applies a Recommendation, THE Advertising_Module SHALL create a `platform_mutation` Operation (Operation_Source `recommendation`) for the corresponding change and route that Operation through Operation_Write_Back and the Sync_State lifecycle defined in Requirements 3 and 4.
2. WHEN an operator activates the AI notification one-click-optimize action, THE Advertising_Module SHALL create a `platform_mutation` Operation (Operation_Source `one_click_optimize`) for each change the action performs and route each Operation through Operation_Write_Back and the Sync_State lifecycle.
3. WHEN the Advertising_Module places a Campaign under AI hosting and that hosting adjusts a bid, budget, keyword, or negative keyword, THE Advertising_Module SHALL create a `platform_mutation` Operation (Operation_Source `ai_hosting`) for each adjustment and route each Operation through Operation_Write_Back and the Sync_State lifecycle.
4. WHERE an applied Recommendation, one-click-optimize action, or hosting adjustment targets an Active_Store that is not write-capable, THE Advertising_Module SHALL set the resulting Operation's Sync_State to `local-only` and THE Frontend SHALL display a saved-locally indication for that Operation.
5. THE Advertising_Module SHALL NOT mark a Recommendation, one-click-optimize action, or hosting adjustment as effective unless a corresponding Operation_Record has reached the `effective` Sync_State.

### Requirement 10: Recommendation Status State Machine

**User Story:** As an advertising operator, I want a recommendation's status to reflect the real outcome of acting on it, so that it is not marked done before the platform confirms.

Reconciliation: this refines the recommendation-apply behavior so that "applied" is replaced by an explicit Recommendation_Status machine tied to the Operation Sync_State.

#### Acceptance Criteria

1. THE Advertising_Module SHALL represent a Recommendation's status using Recommendation_Status with exactly the values `pending`, `applying`, `effective`, `failed`, `dismissed`, `watching`, and `local-only`.
2. WHEN an operator applies a Recommendation against a write-capable Store, THE Advertising_Module SHALL set Recommendation_Status to `applying` while the resulting Operation is in any Unsettled_State (`pending`, `awaiting_approval`, `submitted`, `amazon-processing`, `cancel_requested`, `expired`, or `reconciliation_required`).
3. WHEN the resulting Operation becomes `effective`, THE Advertising_Module SHALL set Recommendation_Status to `effective`.
4. IF the resulting Operation becomes `failed`, THEN THE Advertising_Module SHALL set Recommendation_Status to `failed` and SHALL keep the Recommendation actionable for retry.
5. WHERE an operator applies a Recommendation against a Store that is not write-capable, THE Advertising_Module SHALL set Recommendation_Status to `local-only` as a terminal local state and SHALL NOT set it to `effective`.
6. WHEN an operator dismisses or watch-lists a Recommendation, THE Advertising_Module SHALL set Recommendation_Status to `dismissed` or `watching` respectively without creating a write Operation.
7. WHEN the Frontend displays a Recommendation, THE Frontend SHALL display its Recommendation_Status using a distinct label for each value.
8. THE Advertising_Module SHALL map the resulting Operation's Sync_State to Recommendation_Status using exactly this mapping, where EVERY Unsettled_State maps to `applying` (treating `applying` as the reconciling / in-progress status): `pending` → `applying`; `awaiting_approval` → `applying`; `submitted` → `applying`; `amazon-processing` → `applying`; `cancel_requested` → `applying`; `expired` → `applying`; `reconciliation_required` → `applying`; `effective` → `effective`; `failed` → `failed`; `cancelled` → `pending` (re-actionable); `superseded` → `pending` (re-actionable); `local-only` → `local-only`. A Recommendation that has no applied Operation yet SHALL remain `pending`. The non-terminal `expired` state SHALL NOT map to `failed`.

### Requirement 11: Unknown Recommendation Types Are Not Marked Effective

**User Story:** As an advertising operator, I want recommendations whose type the system cannot act on to be rejected rather than silently marked applied, so that the status reflects real changes.

#### Acceptance Criteria

1. WHEN an operator applies a Recommendation whose Recommendation_Type maps to a defined Operation, THE Advertising_Module SHALL create the corresponding Operation and advance Recommendation_Status only as defined in Requirement 10.
2. IF an operator applies a Recommendation whose Recommendation_Type does not map to a defined Operation, THEN THE Advertising_Module SHALL reject the apply request, SHALL leave the Recommendation in its `pending` state, and SHALL return an error identifying the unsupported Recommendation_Type.
3. WHEN the Frontend receives an unsupported-Recommendation_Type error, THE Frontend SHALL display a message indicating the Recommendation could not be applied and SHALL keep the Recommendation visible in its `pending` state.

### Requirement 12: Local-Only Draft and Honest Local-Record Semantics

**User Story:** As an advertising operator, I want local-only changes treated as drafts that never overwrite Amazon truth and labeled honestly, so that I am not misled into thinking a platform-side action occurred.

#### Acceptance Criteria

1. THE Advertising_Module SHALL treat a local-only value as a draft / simulated pending change surfaced through the Pending_Overlay that SHALL NOT overwrite the field's Amazon-confirmed value.
2. WHEN the Frontend displays a local-only change, THE Frontend SHALL persistently show the indication "仅保存在系统，Amazon 未变更" (saved only in system, Amazon unchanged) for that change.
3. THE Advertising_Module SHALL provide a per-deployment, per-store configuration that is exactly one of "allow local draft" or "forbid operation when not write-capable".
4. WHERE a Store is configured "forbid operation when not write-capable" and is not write-capable, THE Advertising_Module SHALL reject a write Operation and SHALL inform the operator that the operation is not permitted until the Store is write-capable.
5. WHERE a Store is configured "allow local draft" and is not write-capable, THE Advertising_Module SHALL persist the Operation as `local-only` per Requirement 3.
6. THE Advertising_Module SHALL record a Campaign's `origin` (`local` or `amazon_import`) and an Operation's Operation_Source as IMMUTABLE audit facts that are never rewritten, and SHALL NOT change a Campaign's origin or an Operation's source when the Campaign syncs.
7. WHEN a locally-created Campaign's creation Operation reaches the `effective` Sync_State, THE Advertising_Module SHALL store the Amazon-assigned `amazon_campaign_id` on the Campaign, and the Campaign SHALL become visible in the Amazon-synced campaign list by virtue of having `sync_state = effective` and a non-empty `amazon_campaign_id`, WITHOUT rewriting its `origin` or its Operation_Source.
8. WHILE a locally-created Campaign has no `amazon_campaign_id` (its creation Operation has not reached `effective`), THE Advertising_Module SHALL present the Campaign only in a local-drafts view or with a local-source badge and SHALL NOT merge the Campaign into the Amazon-synced campaign list.
9. WHEN the Frontend presents AI hosting (AI托管), THE Frontend SHALL describe it as local hosting that adjusts internally stored values rather than as platform-side eligibility, and SHALL NOT use the retired term "入格".
10. THE `local-only` Sync_State SHALL remain a TERMINAL state with no outgoing transitions; WHEN a Store later becomes write-capable and an operator clicks "submit to Amazon" on a terminal `local-only` draft Operation, THE Advertising_Module SHALL create a NEW `pending` `platform_mutation` Operation (the publish) that carries its OWN new `logicalOperationId` and a `parentOperationId` field referencing the original terminal `local-only` Operation, and SHALL NOT transition the original `local-only` Operation.

### Requirement 13: Search-Term Harvest Correctness

**User Story:** As an advertising operator, I want search-term harvesting to derive the right target campaign and ad group and to apply the chosen action correctly, so that harvesting a negative does not create a positive keyword.

The review found harvesting always produced a positive enabled keyword regardless of the chosen action and did not derive the target campaign/ad group from the term.

#### Acceptance Criteria

1. WHEN an operator harvests a Search_Term without specifying a target, THE Advertising_Module SHALL derive the target Campaign and Ad_Group from the Search_Term's associated campaign and ad group.
2. WHERE an operator specifies an override target, THE Advertising_Module SHALL allow the operator to override the target Campaign, the target Ad_Group, and the match type, and SHALL harvest the Search_Term into the specified target rather than the derived target, validating that the override target is within the caller's effective data scope.
3. WHEN an operator harvests a Search_Term with the Harvest_Action `add_exact`, THE Advertising_Module SHALL create a Keyword in the resolved Ad_Group with an exact match type and an `enabled` status.
4. WHEN an operator harvests a Search_Term with the Harvest_Action `add_phrase`, THE Advertising_Module SHALL create a Keyword in the resolved Ad_Group with a phrase match type and an `enabled` status.
5. WHEN an operator harvests a Search_Term with the Harvest_Action `add_negative`, THE Advertising_Module SHALL create a Negative_Keyword for the resolved Campaign or Ad_Group and SHALL NOT create a positive Keyword.
6. WHEN an operator harvests a Search_Term with the Harvest_Action `watchlist`, THE Advertising_Module SHALL record the Search_Term as watched and SHALL NOT create a Keyword or Negative_Keyword.
7. IF an operator harvests a Search_Term with a Harvest_Action that is not one of `add_exact`, `add_phrase`, `add_negative`, or `watchlist`, THEN THE Advertising_Module SHALL reject the request and return an error identifying the invalid action.
8. IF no override target is supplied and the target Campaign or Ad_Group cannot be derived from the Search_Term, THEN THE Advertising_Module SHALL reject the harvest request and return an error indicating the target could not be resolved.

### Requirement 14: Canonical API Schema for Advertising Response Objects

**User Story:** As a frontend and backend developer, I want a formal canonical API schema with stable field names and shapes that both sides follow, so that the UI and the Backend stop drifting and neither side improvises field names.

The review identified concrete drifts between the Backend VOs/DTOs and the Frontend's expectations. The fix is a single canonical schema both sides conform to, not the Backend matching whatever shape the Frontend currently reads.

#### Acceptance Criteria

1. THE Advertising_Module SHALL define a FORMAL canonical API schema for each advertising response object (at minimum `KeywordVo`, `SearchTermVo`, and `GoalVo`) specifying stable field names, types, and shapes, and BOTH the Frontend and the Backend SHALL conform to that canonical schema.
2. THE canonical `KeywordVo` schema SHALL expose Keyword performance metrics in a single agreed shape (flattened, not nested only under an unread `performance` object) and SHALL include a `bidHealthScore` value, and the Frontend SHALL read those canonical fields.
3. THE canonical `SearchTermVo` schema SHALL include `harvestingStatus`, `periodStart`, and `periodEnd`, and SHALL expose the cost-per-click metric under a single canonical field name that both the Frontend and the Backend use so that `avgCpc` and `cpc` do not diverge.
4. THE canonical `GoalVo` schema SHALL include `campaigns`, `products`, and `trendData`, and SHALL expose a `campaignCount` that equals the length of the `campaigns` collection.
5. WHERE the Frontend currently reads a field name or shape that differs from the canonical schema, THE Frontend SHALL be changed to adapt to the canonical schema rather than the canonical schema being defined as the Frontend's current shape.
6. WHEN the Frontend renders a Keyword, a Search_Term, or a Goal, THE Frontend SHALL read only fields the canonical schema defines.
7. THE Backend SHALL return only Machine_Value_Enum values for enumerated fields in the canonical schema per Requirement 48 and SHALL NOT return display strings.

### Requirement 15: List Filters Are Honored Server-Side

**User Story:** As an advertising operator, I want the filters I set to actually constrain the returned rows, so that the table reflects my filter instead of silently ignoring it.

The review found several list filters that the Backend silently ignored.

#### Acceptance Criteria

1. WHEN an operator requests Search_Terms with a date-range filter or a status filter, THE Backend SHALL return only the Search_Terms matching the supplied date range and status.
2. WHEN an operator requests Keywords with an ad-group filter or a match-type filter, THE Backend SHALL return only the Keywords matching the supplied ad group and match type.
3. WHERE a persisted data source for parent ASIN and targeting goal exists, WHEN an operator requests Campaigns with a parent-ASIN filter or a targeting-goal filter, THE Backend SHALL return only the Campaigns matching the supplied parent ASIN and targeting goal.
4. THE Advertising_Module SHALL define a persisted data source for the parent-ASIN and targeting-goal filters — either persisted columns on the Campaign or a campaign↔product/parent-ASIN association table — WITH a supporting index, before the server-side parent-ASIN or targeting-goal filter is promised; `parentAsin` and `targetingGoal` are non-persistent today and MUST be persisted to be filterable.
5. IF the persisted data source for the parent-ASIN or targeting-goal filter is not provided, THEN that filter SHALL be treated as OUT OF SCOPE and the Frontend SHALL NOT offer it, rather than the Backend silently ignoring the filter.
6. IF a supplied list filter references a field that is not persisted on the queried records, THEN THE Backend SHALL reject the request with a validation error identifying the unsupported filter rather than returning unfiltered results.
7. WHEN the Backend applies a list filter, THE Backend SHALL apply the filter server-side across the full result set rather than only to the rows on the current page.

### Requirement 16: Unified Status Vocabulary

**User Story:** As an advertising operator, I want one consistent status vocabulary everywhere, so that "active" and "enabled" do not behave as two different states across seed data, the engine, hosting, and the UI.

#### Acceptance Criteria

1. THE Advertising_Module SHALL represent Campaign, Keyword, and Target Object_Status using exactly the canonical vocabulary `enabled`, `paused`, and `archived`.
2. THE Backend SHALL store seed-data Object_Status values using the canonical vocabulary.
3. WHEN the recommendation engine, AI hosting, or status-restore logic evaluates an Object_Status, THE Backend SHALL interpret the value using the canonical vocabulary.
4. WHEN the Frontend displays or restores an Object_Status, THE Frontend SHALL map to and from the canonical vocabulary so that an object treated as running by the engine is displayed as `enabled` by the Frontend.
5. WHERE a stored Object_Status value uses a known legacy value, THE Backend SHALL normalize it on read using the explicit migration mapping `active → enabled`, `paused → paused`, and `archived → archived`.
6. IF a stored Object_Status value is an unknown or unrecognized legacy value, THEN THE Backend SHALL write the value to a migration exception list for manual review and SHALL NOT auto-map it to `enabled` or any other canonical value.
7. THE Backend SHALL apply the legacy-to-canonical migration mapping to existing persisted Object_Status values so that every known legacy value is normalized and every unknown value is recorded in the migration exception list rather than left as a non-canonical status.

### Requirement 17: Standardized ACoS Scale and Historical Migration

**User Story:** As an advertising operator, I want ACoS stored and displayed on one fixed scale with a safe historical migration, so that 14.92% is never confused with 1492% and ambiguous legacy values are not silently guessed.

#### Acceptance Criteria

1. THE Advertising_Module SHALL store ACoS as a decimal ratio, such that `0.1492` denotes 14.92% and a 25% target is stored as `0.25`.
2. WHEN the Frontend displays an ACoS value, THE Frontend SHALL multiply the stored ratio by 100 to produce the percentage display consistently across all advertising surfaces.
3. WHEN the recommendation engine compares an ACoS value against a threshold, THE Backend SHALL compare both values as stored decimal ratios.
4. THE Advertising_Module SHALL treat a stored value of `0.1492` and its percentage display of `14.92%` as the same ratio.
5. WHEN historical ACoS data is migrated to the decimal-ratio scale, THE Backend SHALL migrate each column using that column's known source semantics rather than applying a naive "divide by 100 if greater than 1" rule.
6. IF a historical ACoS value is ambiguous under its column's known semantics (for example `1.5`, which could denote 150% or 1.5%), THEN THE Backend SHALL flag the value for manual review rather than guessing its scale.
7. THE Advertising_Module SHALL apply the decimal-ratio convention consistently across ALL layers, not display alone: the database stored values, the API request and response fields, the filter and query parameters (for example `targetAcosMin` and `targetAcosMax`), the form input parsing, and the keyword-intelligence module SHALL each represent ACoS as a decimal ratio, and the historical migration of acceptance criterion 5 SHALL be applied across these layers so that no layer mixes a percentage-scaled value with a decimal-ratio value.

### Requirement 18: Recommendation Engine Correctness

**User Story:** As an advertising operator, I want the recommendation engine to handle empty data, avoid duplicates, and use a sensible default target, so that recommendations are trustworthy.

#### Acceptance Criteria

1. WHEN the recommendation engine evaluates any record, THE Backend SHALL complete the evaluation without raising a null-reference error.
2. WHERE a record evaluated by the recommendation engine has no bid, THE Backend SHALL use the record's Ad_Group default bid as the baseline bid.
3. IF a record evaluated by the recommendation engine has no bid and the record's Ad_Group has no default bid, THEN THE Backend SHALL skip bid-related recommendations for that record.
4. WHERE a record evaluated by the recommendation engine has no sales, THE Backend SHALL treat sales as 0.00 and SHALL evaluate the record under the spend-without-sales (waste) rule rather than under the ACoS-threshold rule, because ACoS is undefined at zero sales.
5. WHEN the recommendation engine generates Recommendations for a Store, THE Backend SHALL NOT produce more than one Recommendation representing the same change for the same target.
6. WHERE no target ACoS is configured for a Campaign under evaluation, THE Backend SHALL resolve the target ACoS in the following order: (a) the Campaign's associated Goal target ACoS, otherwise (b) the product's `target_acos`, otherwise (c) a system default target ACoS of 0.25 (25%).
7. THE system default target ACoS of 0.25 SHALL be a configurable backend value rather than a hardcoded constant.
8. WHEN the recommendation engine generates Recommendations, THE Backend SHALL scope generation to the records of the requested Store only.

### Requirement 19: Budget Window, Multi-Currency Savings, and Trend Comparison

**User Story:** As an advertising operator, I want budget utilization, savings, and trend metrics computed correctly, so that the numbers reflect reality rather than parsed text or wrong windows.

The review found budget utilization used a wrong time window, savings were parsed from recommendation description text, and trend/growth was not a real period-over-period comparison.

#### Acceptance Criteria

1. WHEN the Backend computes budget utilization for a Campaign, THE Backend SHALL compute it over the current budget period rather than over an unrelated time window.
2. WHEN the Backend computes a Recommendation's savings amount, THE Backend SHALL derive the amount from structured monetary values in the Store's reporting currency rather than parsing it from the Recommendation's description text.
3. WHERE amounts contributing to a savings figure are recorded in more than one currency, THE Backend SHALL normalize them to a single reporting currency before producing the savings figure.
4. WHEN the Backend computes a period-over-period trend or growth value, THE Backend SHALL compare the selected period against the immediately preceding period of equal length and compute the growth from those two periods.
5. WHERE the preceding comparison period has no data, THE Backend SHALL indicate the growth value as not-available rather than reporting a growth derived from a zero baseline.
6. WHEN the Backend computes the "AI 带来的销售变化" or "AI 节省花费" figures, THE Backend SHALL compute each as an ESTIMATE using a defined estimation method and SHALL NOT compute it as a naive before/after delta.
7. WHEN the Backend produces an AI sales-change or spend-savings estimate, THE Backend SHALL record the estimation method components: the baseline used (a pre-period baseline or a control/forecast), the attribution window, a confidence indicator, and the algorithm version.
8. WHERE there is no valid baseline for an AI sales-change or spend-savings figure, THE Backend SHALL signal the figure as not estimable and THE Frontend SHALL display "暂不可估算：缺少有效基线" for that figure and SHALL NOT display a fabricated estimate.

### Requirement 20: Goal Data Correctness

**User Story:** As an advertising operator, I want goal metrics scoped to the goal's associated campaigns and goal edits to propagate, so that a goal reflects its own structure rather than the whole store.

#### Acceptance Criteria

1. WHEN the Backend computes a Goal's metrics, THE Backend SHALL scope the metrics to the Campaigns associated with that Goal rather than to all Campaigns in the Store.
2. WHEN an operator updates a Goal, THE Backend SHALL propagate ONLY the following Goal fields to the Goal's associated Campaigns and Ad_Groups: the target ACoS and the optimization goal; and THE Backend SHALL NOT propagate the Goal name, the Goal budget, or the Goal personality default so as to blindly overwrite Campaign-level ad settings.
3. WHERE a Campaign defines a Campaign-level override for a propagated field (for example a Campaign_Personality override or a Campaign-level target ACoS), THE Backend SHALL keep the Campaign-level override in effect and SHALL NOT overwrite it with the propagated Goal value, so that a Campaign-level override always wins over a propagated Goal value.
4. THE Advertising_Module SHALL define the deletion strategy for a Goal's Campaigns and Ad_Groups explicitly, defaulting to detach (preserving the Campaigns and Ad_Groups while removing their Goal association) rather than cascade-delete.
5. WHERE an operator requests cascade deletion of a Goal's generated structure, THE Advertising_Module SHALL delete the associated Campaigns and Ad_Groups only after an explicit confirmation that names the affected objects and counts.

### Requirement 21: Campaign Creation Workflow

**User Story:** As an advertising operator, I want a stepwise campaign creation flow that ends in a platform submission or a labeled local draft, so that creating a campaign is guided and honest about where it lands.

#### Acceptance Criteria

1. THE Frontend SHALL present campaign creation as an ordered, stepwise flow: basic info → ad group → promoted products → keyword/product targeting → budget & bid strategy → draft preview → submit to Amazon → retry-on-failure.
2. WHEN an operator completes the draft-preview step, THE Frontend SHALL display the assembled Campaign for review before submission.
3. WHEN an operator submits a created Campaign and the Active_Store is write-capable, THE Advertising_Module SHALL create a `platform_mutation` Operation (Operation_Source `creation`) and route it through Operation_Write_Back and the Sync_State lifecycle.
4. IF submission of a created Campaign fails, THEN THE Frontend SHALL present a retry control that creates a new Operation attempt per Requirement 4.
5. WHERE the Active_Store is not write-capable, THE Advertising_Module SHALL produce a local draft Campaign labeled "save to system" per the local-only rules in Requirement 12.

### Requirement 22: AI Hosting Capability Boundary and Guards

**User Story:** As an advertising operator, I want AI hosting limited to clearly supported capabilities with explicit guardrails, so that hosting cannot make unbounded or unsupported changes.

#### Acceptance Criteria

1. THE Advertising_Module SHALL scope the executable AI hosting capabilities to the active phase defined in Requirement 54 (V1 = bid adjustment only; V2 = + budget adjustment; V3 = + keyword addition and negative-keyword addition), and the full target capability set SHALL be exactly: bid adjustment, budget adjustment, keyword addition, and negative-keyword addition.
2. THE Frontend SHALL NOT present as executable any AI hosting capability outside the supported set, and SHALL NOT present as executable any capability not yet implemented for the active phase.
3. THE Advertising_Module SHALL enforce a configured minimum and maximum bid on every hosting bid adjustment as a hard Safety_Boundary.
4. THE optimizer SHALL NOT widen a Safety_Boundary limit (including the minimum or maximum bid) to admit a current out-of-range value; the Safety_Boundary is a hard limit that is never auto-expanded.
5. IF a current value already exceeds the Safety_Boundary, THEN THE optimizer SHALL only adjust the value toward the safe range, SHALL NOT move the value further out of range, SHALL flag the Campaign and raise an alert, and MAY pause hosting for that Campaign.
6. THE Advertising_Module SHALL enforce a configured maximum per-change ratio limiting how much a single hosting adjustment may change a value.
7. THE Advertising_Module SHALL enforce a configured maximum daily change count per Campaign under hosting.
8. WHERE a hosting change's absolute change ratio is greater than or equal to the approval threshold, THE Advertising_Module SHALL require approval before the change is submitted, transitioning the Operation to `awaiting_approval` per Requirement 4.
9. THE Advertising_Module SHALL provide a global pause switch that, when engaged, suspends all hosting adjustments.
10. THE Advertising_Module SHALL provide a rollback that reverts a hosting adjustment by recording a compensating Operation per Requirement 8.
11. THE Advertising_Module SHALL resolve a Campaign's effective Safety_Boundary through the configuration hierarchy Campaign override > Goal boundary > Store policy > System default, where the first level that defines a given limit wins and a Campaign-level override always takes precedence over a Goal, Store, or System value.
12. WHEN AI hosting is turned off for a Campaign or the global pause switch of criterion 9 is engaged, THE Advertising_Module SHALL stop generating NEW hosting Operations for the affected Campaign(s), and SHALL define the fate of existing in-flight hosting Operations as follows rather than silently dropping them: Operations in `awaiting_approval` SHALL be cancelled (transitioned to `cancelled` per Requirement 4), and Operations that are already submitted or in flight (`submitted` or `amazon-processing`) SHALL be left to resolve their platform final state (optionally routed to `cancel_requested` per Requirement 4) rather than silently dropped.

### Requirement 23: AI Notification Production Mechanism

**User Story:** As an advertising operator, I want notifications produced by a defined mechanism, so that the notification configuration is not shown when nothing generates notifications.

#### Acceptance Criteria

1. THE Advertising_Module SHALL define, for each notification type, the trigger source that generates the notification, the generation frequency, and a dedupe key.
2. WHEN a notification trigger fires, THE Advertising_Module SHALL produce a notification only if no existing open notification shares the same dedupe key.
3. WHEN a notification's underlying condition is resolved or invalidated, THE Advertising_Module SHALL close the stale notification.
4. THE Advertising_Module SHALL consume the notification configuration to decide which notifications to generate and how to route them.
5. WHERE no producer exists for a notification type, THE Frontend SHALL NOT show that notification type's configuration entry.

### Requirement 24: Consistent Scope and Permission Enforcement Across Advertising Endpoints

**User Story:** As a security administrator, I want every advertising read and write endpoint to enforce data scope and permissions consistently, so that users cannot read or change data outside their store and role.

Reconciliation: the `Permission_Service` and `Data_Scope_Service` are owned by `core-platform-completion`; this requirement requires the Advertising_Module to apply them uniformly.

#### Acceptance Criteria

1. WHEN a caller invokes any advertising read or write endpoint for keyword, search-term, recommendation, goal, ad-group, product-ad, negative-keyword, or bid-change resources, THE Backend SHALL apply the Data_Scope_Service to restrict the operation to the caller's effective data scope.
2. WHEN a caller invokes an advertising endpoint that requires a permission, THE Backend SHALL verify the required permission through the Permission_Service before the operation executes.
3. IF a caller lacks the required permission for an advertising operation, THEN THE Backend SHALL reject the request with an HTTP 403 status and SHALL NOT perform the operation.
4. THE Backend SHALL resolve the acting user from the authenticated security context for every advertising endpoint rather than using a hardcoded user identity.

### Requirement 25: Per-Record Ownership Validation

**User Story:** As a security administrator, I want per-ID advertising operations to confirm the record belongs to the caller's store, so that a user cannot act on another store's records by guessing identifiers.

#### Acceptance Criteria

1. WHEN a caller invokes an advertising operation that references a specific record by identifier, THE Backend SHALL verify that the referenced record belongs to a Store within the caller's effective data scope before performing the operation.
2. IF a referenced advertising record does not belong to a Store within the caller's effective data scope, THEN THE Backend SHALL reject the request with an HTTP 403 status and SHALL NOT disclose the record's contents.
3. WHEN a caller requests recommendation generation or a trend computation for a Store, THE Backend SHALL reject the request with an HTTP 403 status IF the caller does not own the requested Store.
4. WHEN a caller invokes a bulk advertising operation referencing multiple record identifiers, THE Backend SHALL verify ownership of each referenced record and SHALL apply the cross-store batch-rejection rule defined in Requirement 6.

### Requirement 26: Permission-Driven Advertising Action Controls

**User Story:** As a logged-in operator, I want the advertising UI to hide or disable actions I am not permitted to perform, so that I am not offered controls that will be rejected.

Reconciliation: the permission-driven UX pattern originates in `core-platform-completion` Requirement 3.1; this requirement applies it to advertising-specific controls.

#### Acceptance Criteria

1. WHERE the logged-in user lacks the permission required to manage keywords, THE Frontend SHALL hide or disable the keyword create, edit, and harvest controls.
2. WHERE the logged-in user lacks the permission required to approve advertising actions, THE Frontend SHALL hide or disable the recommendation-apply and one-click-optimize controls.
3. WHEN the logged-in user's permission set changes, THE Frontend SHALL re-render the advertising action controls according to the updated permission set on the next permission fetch or navigation.
4. WHERE an advertising action control is hidden or disabled for lack of permission, THE Frontend SHALL not issue the corresponding Backend request.

### Requirement 27: Full Advertising Permission Matrix

**User Story:** As a security administrator, I want a complete permission matrix across advertising resources and actions, so that every advertising capability maps to a defined permission code.

#### Acceptance Criteria

1. THE Backend SHALL enforce the following permission matrix, where each cell names the permission code required to perform the action on the resource:

   | Resource | view | create-edit | delete | approve | platform-execute |
   |---|---|---|---|---|---|
   | Campaign | advertising:view | advertising:manage | advertising:manage | advertising:approve | advertising:execute |
   | AdGroup | advertising:view | advertising:manage | advertising:manage | advertising:approve | advertising:execute |
   | ProductAd | advertising:view | advertising:manage | advertising:manage | advertising:approve | advertising:execute |
   | Target | keyword:view | keyword:manage | keyword:manage | advertising:approve | keyword:apply |
   | Keyword | keyword:view | keyword:manage | keyword:manage | advertising:approve | keyword:apply |
   | NegativeKeyword | keyword:view | keyword:manage | keyword:manage | advertising:approve | keyword:apply |
   | SearchTerm | advertising:view | keyword:manage | keyword:manage | advertising:approve | keyword:apply |
   | Recommendation | advertising:view | advertising:manage | advertising:manage | advertising:approve | advertising:execute |
   | Operation | operation:view | advertising:manage | N/A (audit record — archival only per retention policy) | advertising:approve | advertising:execute |
   | SyncLog | advertising:view | advertising:manage | N/A (audit record — archival only per retention policy) | advertising:approve | advertising:execute |
   | BidChange | advertising:view | advertising:manage | N/A (audit record — archival only per retention policy) | advertising:approve | advertising:execute |
   | Goal | advertising:view | advertising:manage | advertising:manage | advertising:approve | advertising:execute |
   | Portfolio | advertising:view | advertising:manage | advertising:manage | advertising:approve | advertising:execute |
   | Diagnosis | advertising:view | advertising:manage | advertising:manage | advertising:approve | advertising:execute |
   | Hosting | advertising:view | hosting:manage | hosting:manage | advertising:approve | advertising:execute |
   | Notification | advertising:view | advertising:manage | advertising:manage | advertising:approve | advertising:execute |

2. THE Backend SHALL seed any permission code introduced by this matrix that does not already exist in the seed. The seed today contains `advertising:view`, `advertising:manage`, `advertising:approve`, `keyword:view`, `keyword:manage`, and `keyword:apply`; this matrix introduces `advertising:execute`, `operation:view`, and `hosting:manage`, all of which SHALL be added to the permission seed. The `advertising:execute` permission is only meaningful once the Write_Connector prerequisite milestone of Requirement 53 is reached.
3. THE Operation, SyncLog, and BidChange resources are audit records; THE Backend SHALL NOT expose a normal delete permission for them (their delete cell is N/A) and SHALL permit removal only by archival under a defined retention policy rather than by an interactive delete action.
4. WHEN a caller invokes an advertising action, THE Backend SHALL require the permission code defined by the matrix cell for that resource and action.
5. WHERE a matrix cell's permission code is not held by the caller, THE Backend SHALL reject the action with an HTTP 403 status per Requirement 24.
6. THE permission matrix is built from the `advertising:*` and `keyword:*` permission families (action-level codes such as `advertising:view`, `advertising:manage`, `advertising:approve`, `advertising:execute`, `keyword:view`, `keyword:manage`, `keyword:apply`) plus the distinct codes `operation:view` and `hosting:manage` introduced where a separate control is warranted; THE Backend SHALL replace broad `advertising:view`-only route guards with the action-level code defined by this matrix for each route's resource and action, and SHALL NOT claim fully per-resource granular permissions beyond the codes this matrix defines.
7. THE Frontend SHALL drive the visibility and enabled state of each advertising control from the same permission code the matrix assigns to that control's action.

### Requirement 28: Four-Group Tab Information Architecture

**User Story:** As an advertising operator, I want the flat tabs regrouped into clear functional groups with a precise mapping, so that I can find ad management, search-term, optimization, and audit surfaces quickly.

Reconciliation: the underlying eleven tabs and their data are owned by `app-functionality-completion`; this requirement regroups their presentation without removing the data surfaces.

#### Acceptance Criteria

1. THE Frontend SHALL present the advertising tabs organized into exactly four groups using the following precise mapping:
   - **广告管理** (ad management): 广告活动, 广告组, 推广商品, 投放对象
   - **搜索词管理** (search-term management): 搜索词, 否定投放, 购买的其他商品
   - **智能优化** (intelligent optimization): AI托管, 优化建议, 竞价与预算 (含竞价调整 + SP预算上限)
   - **记录与审计** (records and audit): 操作日志, 同步记录, 失败任务
2. THE Frontend SHALL assign every existing advertising tab to exactly one of the four groups, with no tab data surface removed.
3. WHEN an operator selects a group, THE Frontend SHALL display the tabs belonging to that group.
4. THE Frontend SHALL use consistent state naming for advertising statuses across all four groups so that the same underlying state is labeled identically in every group.

### Requirement 29: Context Bar and Applied-Filter Chips

**User Story:** As an advertising operator, I want a top context bar and visible applied-filter chips with filters placed above charts and tables, so that I always know the data context and what filters are active.

#### Acceptance Criteria

1. THE Frontend SHALL display a Context_Bar showing the active store, site, advertising account, currency, data date, and last-sync time.
2. THE Frontend SHALL render the filter controls above the KPI_Panel and the table rather than below them.
3. WHEN one or more filters are applied, THE Frontend SHALL display a Filter_Chip for each applied filter condition.
4. WHEN an operator removes a Filter_Chip, THE Frontend SHALL remove that filter condition and re-request the table data reflecting the remaining filters.
5. WHERE no filters are applied, THE Frontend SHALL display no Filter_Chips.

### Requirement 30: Collapsible KPI and Trend Panel

**User Story:** As an advertising operator, I want a collapsible KPI and trend panel with a default date range and comparison period, so that I can focus on the table or expand the metrics as needed.

#### Acceptance Criteria

1. THE Frontend SHALL present the KPI_Panel as a collapsible region that an operator can collapse and expand.
2. THE KPI_Panel SHALL provide a date-range selector and a comparison-period selector.
3. THE KPI_Panel SHALL default the date range to the last 7 days compared to the previous 7 days.
4. WHEN the KPI_Panel computes its date range and comparison period, THE Frontend SHALL compute the dates in the Marketplace_Timezone of the Active_Store.
5. WHEN an operator changes the date range or comparison period, THE Frontend SHALL request the corresponding metrics and trend data and update the KPI_Panel.
6. WHEN the KPI_Panel displays a comparison, THE Frontend SHALL display the period-over-period change computed per Requirement 19.

### Requirement 31: Reusable Table Foundation Completion

**User Story:** As an advertising operator, I want the partially delivered shared table base completed for advertising tables, so that totals, pagination, column management, export, and saved views actually work in the advertising UI.

Reconciliation: `platform-ux-logistics-enhancements` delivered the base, where `total` and `tableKey` props exist but are not wired and the pagination, column-management, export, and saved-view entries are absent in the advertising UI. THIS spec completes them.

#### Acceptance Criteria

1. THE Frontend SHALL wire the `total` prop through to each advertising table so that the table renders the server-reported total record count.
2. THE Frontend SHALL wire the `tableKey` prop through to each advertising table so that per-user, per-table column configuration and saved views resolve by table key.
3. THE Frontend SHALL render a pagination control on each advertising table that issues server-side page requests.
4. THE Frontend SHALL render a column-management entry and column-management UI on each advertising table.
5. THE Frontend SHALL render an export entry on each advertising table.
6. THE Frontend SHALL render a saved-view entry on each advertising table.

### Requirement 32: Advertising Table Toolbar

**User Story:** As an advertising operator, I want a consistent table toolbar with result count, bulk operations, new, refresh, export, and column management, so that I can operate on advertising tables consistently.

#### Acceptance Criteria

1. THE Frontend SHALL present a Table_Toolbar above each advertising table exposing the current result count, the bulk-operations control, the new-record control, the refresh control, the export control, and the column-management control.
2. WHEN the table's filtered result set changes, THE Frontend SHALL update the displayed result count to match the total number of records matching the active filters across all pages.
3. WHEN an operator activates the refresh control, THE Frontend SHALL re-request the current table data and display the refreshed result.
4. WHERE a logged-in user lacks the permission for the new-record or a bulk operation, THE Frontend SHALL hide or disable that control per Requirement 26.

### Requirement 33: Server-Side Pagination, Sorting, Pinned Columns, and Detail Drawer

**User Story:** As an advertising operator, I want advertising tables to paginate, sort, pin columns, and open a detail drawer server-side, so that large advertising datasets stay performant and inspectable.

#### Acceptance Criteria

1. WHEN an operator paginates or sorts an advertising table, THE Backend SHALL apply the pagination and sort parameters server-side and return only the requested page in the requested order.
2. WHEN an operator designates 1 to 5 columns as pinned in an advertising table, THE Frontend SHALL hold those columns fixed at the start of the table while the remaining columns scroll horizontally.
3. WHEN an operator opens a row's detail drawer, THE Frontend SHALL display the full record detail for that row without navigating away from the table.
4. WHEN an operator sorts an advertising table by a column, THE Backend SHALL order the full result set by that column rather than ordering only the current page.

### Requirement 34: Debounced Search, Apply-Filters, Saved Views, and URL-Encoded Filters

**User Story:** As an advertising operator, I want debounced search, an explicit apply-filters action, saved views, and filters encoded in the URL, so that filtering is efficient and shareable.

#### Acceptance Criteria

1. WHEN an operator types in an advertising table's search field, THE Frontend SHALL debounce the search input before issuing a request.
2. THE Frontend SHALL provide an apply-filters control that issues the filtered request only when activated, for filters configured to apply explicitly.
3. WHEN an operator saves the current advertising columns, filters, and sort order as a Saved_View, THE Backend SHALL persist the Saved_View scoped to the requesting user and the advertising table key, reusing the Saved_View capability from `platform-ux-logistics-enhancements`.
4. WHEN an operator applies an advertising filter or sort, THE Frontend SHALL encode the active filters and sort into the page URL.
5. WHEN an operator opens an advertising table via a URL that encodes filters and sort, THE Frontend SHALL apply the encoded filters and sort on load.

### Requirement 35: Fixed Status Enumerations

**User Story:** As an advertising operator, I want status filter options to come from a fixed enumeration, so that the available statuses do not change based on whichever rows happen to be loaded.

#### Acceptance Criteria

1. THE Frontend SHALL populate each advertising status filter from a fixed enumeration of the possible status values for that field.
2. THE Frontend SHALL NOT derive the set of available status filter options from the rows currently loaded on the page.
3. WHEN no loaded row has a given status value, THE Frontend SHALL still offer that status as a selectable filter option.

### Requirement 36: Bulk-Operation Confirmation, Per-Item Results, and Double-Submit Prevention

**User Story:** As an advertising operator, I want bulk operations to confirm before running, report per-item results, and prevent double submission, so that I do not accidentally apply or duplicate a destructive change.

#### Acceptance Criteria

1. WHEN an operator activates a Bulk_Operation on selected rows, THE Frontend SHALL present a confirmation that states the operation and the number of affected rows before executing.
2. WHEN a confirmed Bulk_Operation's API call completes, THE Frontend SHALL display the per-item result in the uniform format defined in Requirement 6 indicating, for each affected row, the creation result (whether an Operation was created) and the failure reason where creation failed, and THE Frontend SHALL make clear that API completion means Operations were created, NOT that Amazon has applied them; THE Frontend SHALL distinguish the creation result, the subsequent submission result, and the platform final result (effective/failed) rather than presenting API completion as platform success.
3. WHILE a Bulk_Operation or a single Operation is in progress, THE Frontend SHALL disable the control that initiated it so that the same Operation cannot be submitted again until the in-progress Operation completes.
4. WHILE an advertising data request is in progress, THE Frontend SHALL display a loading indication for the affected table or panel.

### Requirement 37: Export Scope Disclosure

**User Story:** As an advertising operator, I want export to state whether it covers the current page or all filtered rows, so that I know what the exported file contains.

#### Acceptance Criteria

1. WHEN an operator activates export on an advertising table, THE Frontend SHALL present whether the export covers the current page only or all rows matching the active filters before producing the file.
2. WHEN an operator chooses to export all filtered rows, THE Backend SHALL produce a file containing the full filtered result set rather than only the current page.
3. WHEN an operator chooses to export the current page, THE Backend SHALL produce a file containing only the rows on the current page.
4. THE exported file SHALL contain only the columns currently visible in the operator's column configuration.

### Requirement 38: Multi-Pinned-Column Rendering and Currency-Aware Formatting

**User Story:** As an advertising operator, I want multiple pinned columns to render at their correct horizontal offsets and money displayed in the store's currency, so that pinned columns do not overlap and amounts are unambiguous.

The review found multiple pinned columns were all positioned at left-0, overlapping each other.

#### Acceptance Criteria

1. WHEN two or more columns are pinned, THE Frontend SHALL position each pinned column at a horizontal offset equal to the cumulative width of the pinned columns preceding it, so that pinned columns do not overlap.
2. WHEN the Frontend displays a monetary advertising value, THE Frontend SHALL format the value using the currency of the Active_Store shown in the Context_Bar.
3. WHERE advertising values span more than one currency in a single view, THE Frontend SHALL display each value with its own currency rather than assuming a single currency.

### Requirement 39: Single Source of Truth for Filters, KPI, and Table

**User Story:** As an advertising operator, I want the filters, KPI panel, and table to share one set of query conditions, so that what I see is internally consistent.

#### Acceptance Criteria

1. THE Frontend SHALL drive the Filter controls, the KPI_Panel, and the table from the SAME set of query conditions as a single source of truth.
2. WHEN an operator changes a query condition, THE Frontend SHALL apply the change to the Filter controls, the KPI_Panel, and the table together.
3. THE Frontend SHALL NOT maintain a separate, divergent set of query conditions for the KPI_Panel and the table.

### Requirement 40: Store Switch and Unsaved-Edit Guards

**User Story:** As an advertising operator, I want switching stores and leaving with unsaved edits handled safely, so that I do not carry stale context across stores or lose work silently.

#### Acceptance Criteria

1. WHEN an operator switches the Active_Store, THE Frontend SHALL clear the current row selection, drafts, and filters that do not apply to the newly selected Store.
2. WHEN an operator attempts to leave the advertising module while an unsaved edit is in progress, THE Frontend SHALL present a confirmation before navigating away.
3. IF the operator cancels the leave confirmation, THEN THE Frontend SHALL remain on the advertising module and preserve the unsaved edit.

### Requirement 41: Explicit UI States

**User Story:** As an advertising operator, I want every advertising view to define its loading, empty, partial-error, stale, and no-permission states, so that the UI never shows an ambiguous blank or misleading screen.

#### Acceptance Criteria

1. WHILE an advertising view is loading data, THE Frontend SHALL display a loading state.
2. WHERE an advertising query returns no records, THE Frontend SHALL display an empty-data state distinct from the loading state.
3. WHERE an advertising response is partially successful with some records or panels failing, THE Frontend SHALL display a partial-error state that identifies what failed while showing the successful content.
4. WHERE an advertising view shows cached data that is known to be stale, THE Frontend SHALL display a stale-cache state indicating the data may be out of date.
5. WHERE the logged-in user lacks permission to view an advertising surface, THE Frontend SHALL display a no-permission state rather than an empty table.

### Requirement 42: Accessibility

**User Story:** As an operator using assistive technology, I want the advertising module to be keyboard and screen-reader accessible, so that I can operate it without a mouse.

#### Acceptance Criteria

1. THE Frontend SHALL support keyboard navigation across the advertising tabs, toolbar controls, table rows, and drawers.
2. WHEN a drawer or modal opens, THE Frontend SHALL move focus into the opened element and SHALL return focus to the triggering control when it closes.
3. WHEN an operator presses the escape key while a drawer is open, THE Frontend SHALL close the drawer.
4. THE Frontend SHALL provide ARIA roles and labels for the advertising tables, toolbar controls, filter chips, and status indicators.

### Requirement 43: Responsive and Mobile Strategy

**User Story:** As an advertising operator on a small screen, I want a usable read-and-act experience rather than a broken wide table, while accepting that advertising is a desktop-dense workflow, so that the module degrades gracefully without pretending to support full editing on mobile.

#### Acceptance Criteria

1. THE Frontend SHALL NOT convert all advertising tables to cards as a blanket mobile strategy; advertising is a desktop-dense workflow and the full editing experience is a desktop experience.
2. WHERE the viewport width is below 768 pixels, THE Frontend SHALL present a responsive read-oriented alternative (such as a stacked list of the key fields) and SHALL provide only view, pause, and approve actions, and SHALL NOT offer full bulk editing on small screens.
3. THE Frontend SHALL define 768 pixels as the minimum width at which the full advertising table and full editing controls are rendered.
4. WHEN the viewport crosses the minimum-width threshold, THE Frontend SHALL switch between the full desktop table and the responsive read-oriented alternative accordingly.

### Requirement 44: Cross-Page Selection Semantics

**User Story:** As an advertising operator, I want "select all" to distinguish the current page from all filtered results, so that a bulk operation applies to exactly the rows I intend.

#### Acceptance Criteria

1. WHEN an operator selects all rows, THE Frontend SHALL present two distinct choices: "select current page" and "select all filtered results".
2. WHEN an operator chooses "select current page", THE Frontend SHALL include only the rows rendered on the current page in the selection.
3. WHEN an operator chooses "select all filtered results", THE Frontend SHALL define the selection by the active filter conditions across all pages and THE Backend SHALL apply the resulting Bulk_Operation to every record matching those filters.
4. WHEN a selection spans all filtered results, THE Frontend SHALL display the count of records that the selection covers.

### Requirement 45: Context Preservation Across Tab Switches

**User Story:** As an advertising operator, I want my filters, selection, and in-progress edits preserved when I switch tabs and come back, so that I do not lose my working context.

#### Acceptance Criteria

1. WHEN an operator switches from one advertising tab to another and returns, THE Frontend SHALL restore the originating tab's active filters.
2. WHEN an operator switches from one advertising tab to another and returns, THE Frontend SHALL restore the originating tab's row selection where the selected rows are still present in the data.
3. WHERE an operator has an unsaved in-progress edit when switching tabs, THE Frontend SHALL preserve the in-progress edit on return rather than discarding it.

### Requirement 46: Scoped Smart Diagnosis Without Duplicate Recommendations

**User Story:** As an advertising operator, I want smart diagnosis scoped to its target product and to stop generating duplicate recommendations, so that diagnosis does not pollute the recommendation set with whole-store noise.

The review found diagnosis analyzed the whole store regardless of parent ASIN and auto-generated duplicate recommendations.

#### Acceptance Criteria

1. WHEN a Smart_Diagnosis runs for a product identified by a parent ASIN, THE Backend SHALL scope the analysis to the campaigns and records associated with that parent ASIN.
2. THE Backend SHALL NOT include records outside the diagnosed parent ASIN in a Smart_Diagnosis result.
3. WHEN a Smart_Diagnosis produces findings, THE Backend SHALL NOT auto-generate a Recommendation that duplicates an existing unapplied Recommendation for the same target.
4. WHEN a Smart_Diagnosis produces findings, THE Backend SHALL apply the duplicate-suppression defined in Requirement 18 before persisting any generated Recommendation.

### Requirement 47: Honest Diagnosis Scheduling

**User Story:** As an advertising operator, I want diagnosis frequency claims backed by a real scheduler or not claimed at all, so that the UI does not promise daily or weekly runs that never happen.

Reconciliation: the scheduling engine is owned by `core-platform-completion` (Requirement 4.1); this requirement requires diagnosis frequency to be driven by that engine or not displayed.

#### Acceptance Criteria

1. WHERE a Smart_Diagnosis is configured with a daily or weekly frequency, THE Backend SHALL register the diagnosis with the scheduling engine so that the diagnosis runs at the configured frequency.
2. WHEN a scheduled Smart_Diagnosis runs, THE Backend SHALL record the last-diagnosis time.
3. IF no scheduler drives a configured diagnosis frequency, THEN THE Frontend SHALL NOT display a recurring-frequency claim for that diagnosis.
4. WHEN the Frontend displays a Smart_Diagnosis frequency, THE Frontend SHALL display the frequency that the scheduling engine is configured to run.

### Requirement 48: AI Hosting Terminology Unification

**User Story:** As an advertising operator, I want one honest, consistent vocabulary for AI hosting that never implies Amazon official eligibility, so that the copy describes what the system actually does.

Reconciliation: AI hosting copy is surfaced by this spec and by the Insight Agent owned by `app-functionality-completion`; both MUST use this unified vocabulary. The underlying hosting state and write-back are owned by `core-platform-completion`; this requirement constrains presentation only.

#### Acceptance Criteria

1. THE Advertising_Module SHALL NOT use the term "入格" in any advertising copy, label, status, or configuration, because it implies Amazon official eligibility.
2. THE Frontend SHALL use the following advertising DISPLAY vocabulary consistently across every advertising surface, replacing the term on the left with the term on the right:
   - 风险偏好 → AI人格
   - AI入格 / 已入格 → AI托管状态 (display values 托管中 / 未托管)
   - 托管目标 → 优化目标
   - 优化频率 → 决策频率
   - 自动竞价优化 → 允许 AI 调整竞价
   - 自动否定 → 允许 AI 添加否定词
   - 自动扩展关键词 → 允许 AI 添加关键词
   - ACOS最近 → 近7天 ACoS
3. WHERE an advertising surface previously displayed a term listed on the left of the mapping in acceptance criterion 2, THE Frontend SHALL display the corresponding term on the right of that mapping.
4. THE Backend SHALL return STABLE MACHINE values and SHALL NOT return Chinese display strings: AI_Hosting_Status as `hosted` or `not_hosted`; AI_Personality as `conservative`, `balanced`, or `aggressive`; Optimization_Goal as the machine enum `profit_first`, `sales_growth`, `rank`, or `clearance`; and Object_Status as `enabled`, `paused`, or `archived`.
5. THE Frontend SHALL translate each Machine_Value_Enum value returned by the Backend into its display copy (for example `hosted` → 托管中, `not_hosted` → 未托管, `conservative` → 常规型, `balanced` → 平衡型, `aggressive` → 激进型), so that this terminology requirement constrains DISPLAY only and the backend contract returns machine values.
6. THE Frontend SHALL render the AI_Hosting_Status, AI_Personality, and Optimization_Goal labels using the same display vocabulary in the advertising list, the AI托管 settings drawer, the AI托管 overview, and the Insight Agent surfaces.
7. THE Frontend SHALL use DISTINCT wording for "关闭AI托管" (turning off AI hosting for a Campaign — a `local_configuration` change to the hosting policy) and "取消平台操作" (cancelling an in-flight platform Operation per Requirement 4), and SHALL NOT conflate the two: the two actions SHALL never share the same label, button text, or confirmation copy, because turning off AI hosting does not cancel any already-submitted platform Operation and cancelling a platform Operation does not turn off AI hosting.

### Requirement 49: Executable AI Personality

**User Story:** As an advertising operator, I want the AI personality to be an enforceable optimizer policy rather than a stored label, so that selecting a personality actually changes how the AI optimizer adjusts my campaigns.

The review found that the AI optimizer (`AiHostingOptimizer`) currently adjusts only LOCAL keyword bids and only stores/displays the personality field (`riskPreference`, which today exists only on `GoalEntity`), without using it to drive any adjustment. This requirement makes the personality an enforceable optimizer policy with concrete numeric controls, a defined data location, and a resolution rule, applied only to the capabilities implemented for the active phase (Requirement 54).

Reconciliation: write-back, approval gating, and scheduling are owned by `core-platform-completion`; AI decisions reuse the Operation_Record defined in Requirement 8 and route through Operation_Write_Back per Requirement 2. The Insight Agent in `app-functionality-completion` consumes the same AI_Personality vocabulary. Superseding NOT-yet-submitted Operations and routing already-submitted/in-flight Operations through the `cancel_requested` path when the personality changes (criterion 14 below) are LEGAL transitions defined in Requirement 4.

#### Acceptance Criteria

1. THE Advertising_Module SHALL represent AI_Personality as exactly one of three canonical machine values: `conservative` (display 常规型), `balanced` (display 平衡型), and `aggressive` (display 激进型); the alternate display name 稳健型 SHALL NOT be used anywhere.
2. THE Advertising_Module SHALL store the AI_Personality DEFAULT on the Goal (the existing `risk_preference` field on `GoalEntity`), SHALL add a NEW optional campaign-level AI_Personality override field (Campaign_Personality) to the Campaign, and SHALL add a Store-level default personality (Store_Default_Personality); THE Advertising_Module SHALL resolve a Campaign's effective AI_Personality with the precedence Campaign_Personality override > Goal_Personality_Default > Store_Default_Personality > the system fallback `balanced`.
3. WHEN no Campaign_Personality override is set, THE Advertising_Module SHALL resolve the Campaign's effective AI_Personality to the Goal_Personality_Default of the Campaign's associated Goal; WHERE the Campaign's `goal_id` is null or the associated Goal defines no personality default, THE Advertising_Module SHALL resolve to the Store_Default_Personality; and WHERE no Store_Default_Personality is set, THE Advertising_Module SHALL resolve to the system fallback `balanced`.
4. WHEN the AI optimizer evaluates a hosted Campaign, THE Advertising_Module SHALL apply the resolved AI_Personality's Personality_Policy to control the adjustments permitted for the active phase (Requirement 54), and SHALL NOT apply controls for capabilities not yet implemented in the active phase.
5. THE Advertising_Module SHALL encode the per-personality control values as a configurable backend Personality_Policy table using CONCRETE NUMERIC fields with the following defaults (all values configurable backend values), where each approval ratio is set BELOW its corresponding maximum ratio so that approval can trigger before the maximum is reached:

   | Field | conservative (常规型) | balanced (平衡型) | aggressive (激进型) |
   |---|---|---|---|
   | minClicks | 50 | 20 | 10 |
   | minOrders | 5 | 3 | 1 |
   | lookbackDays | 30 | 14 | 7 |
   | minConversionRate | 0.10 | 0.07 | 0.03 |
   | negativeConfidenceThreshold | 0.90 | 0.75 | 0.60 |
   | acosToleranceRatio | 0.05 | 0.15 | 0.30 |
   | approvalBidChangeRatio | 0.03 | 0.07 | 0.15 |
   | approvalBudgetChangeRatio | 0.03 | 0.10 | 0.20 |
   | maxBidIncreaseRatio | 0.05 | 0.10 | 0.20 |
   | maxBidDecreaseRatio | 0.10 | 0.15 | 0.25 |
   | maxDailyBudgetIncreaseRatio | 0.05 | 0.15 | 0.30 |
   | adjustmentCooldownHours | 24 | 12 | 4 |
   | exploreBudgetRatioMin | 0.00 | 0.05 | 0.15 |
   | exploreBudgetRatioMax | 0.05 | 0.15 | 0.30 |
   | keywordExpansionMode | off | suggest | auto |
   | negativeKeywordMode | suggest | approval | auto |
   | keywordConfidenceThreshold | 0.90 | 0.75 | 0.60 |
   | maxKeywordsAddedPerDay | 0 | 5 | 20 |
   | maxNegativesAddedPerDay | 5 | 15 | 50 |

   The `keywordExpansionMode`, `negativeKeywordMode`, `keywordConfidenceThreshold`, `maxKeywordsAddedPerDay`, and `maxNegativesAddedPerDay` fields govern the V3 keyword-expansion and negative-keyword capabilities (Requirement 54) and SHALL apply only when the active phase includes those capabilities.

6. THE Personality_Policy table SHALL be a configurable backend value and SHALL NOT be hardcoded in the Frontend, and SHALL NOT be expressed as vague qualitative levels (no "high/medium/low data volume", "strict conditions", "high-confidence", "extreme changes", or "low/medium/high ACoS tolerance").
7. THE Advertising_Module SHALL treat Optimization_Goal, AI_Personality, and Safety_Boundary as three independent concepts, where Optimization_Goal defines what the AI aims for (`profit_first`, `sales_growth`, `rank`, or `clearance`), AI_Personality defines how aggressively and how riskily the AI acts to reach that goal, and Safety_Boundary defines the hard limits (maxBid, maxCpc, maximum daily budget increase, approval rules, and platform sync state) the AI can never cross.
8. WHEN an operator selects an Optimization_Goal, THE Advertising_Module MAY recommend an AI_Personality, but THE Advertising_Module SHALL require explicit operator confirmation before changing AI_Personality and SHALL NOT silently switch AI_Personality when an Optimization_Goal is chosen.
9. WHEN the AI optimizer makes a decision, THE Advertising_Module SHALL record on the Operation_Record the trigger metric, the resolved AI_Personality used, the Personality_Rule_Version, the personality-allowed maximum magnitude, the actual applied magnitude, the decision reason, the before value, the after value, the predicted impact, whether approval is required, and the Amazon sync result.
10. THE AI optimizer SHALL NOT bypass maxBid, maxCpc, maximum daily budget increase, approval rules, or the Safety_Boundary, and SHALL NOT widen any Safety_Boundary limit per Requirement 22.
11. WHERE a personality-allowed magnitude exceeds the Safety_Boundary, THE Advertising_Module SHALL apply the Safety_Boundary limit so that the Safety_Boundary always takes precedence over the AI_Personality, and THE Advertising_Module SHALL resolve the effective Safety_Boundary through the configuration hierarchy Campaign override > Goal boundary > Store policy > System default per Requirement 22, where the first level that defines a given limit wins and a Campaign-level override always takes precedence.
12. WHEN an operator changes AI_Personality, THE Advertising_Module SHALL NOT immediately recompute and execute all hosted Campaigns.
13. WHEN an operator changes AI_Personality, before saving the change THE Frontend SHALL display the count of Campaigns affected by the change.
14. WHEN an operator changes AI_Personality, THE Advertising_Module SHALL let the operator choose whether existing pending Operations continue under the previous AI_Personality or are recomputed under the new AI_Personality; WHERE the operator chooses to recompute, THE Advertising_Module SHALL, for affected Operations that are NOT yet submitted (`pending` or `awaiting_approval`), transition them DIRECTLY to `superseded`, and for affected Operations that are already submitted or in flight (`submitted` or `amazon-processing`), route them through the `cancel_requested` path of Requirement 4 rather than directly to `superseded`, and SHALL create new Operations under the new AI_Personality.
15. WHEN an operator changes AI_Personality, THE Advertising_Module SHALL write the personality change to the operation log.
16. WHEN an operator performs a bulk AI_Personality change, THE Frontend SHALL require a second confirmation before applying the change.
17. WHEN an operator selects the `aggressive` AI_Personality, THE Frontend SHALL display an additional risk warning.
18. WHERE the Active_Store is not write-capable, THE Frontend SHALL display the indication "仅影响系统内建议，不会修改 Amazon" when an operator changes AI_Personality.
19. THE Advertising_Module SHALL trigger the approval requirement WHEN an Operation's absolute change ratio is greater than or equal to the applicable approval threshold (`approvalBidChangeRatio` for a bid change or `approvalBudgetChangeRatio` for a budget change) and SHALL transition the Operation to `awaiting_approval` per Requirement 4; because each approval ratio is configured strictly below its corresponding maximum ratio (`maxBidIncreaseRatio` / `maxDailyBudgetIncreaseRatio`), approval can actually trigger before the maximum is reached.

### Requirement 50: AI Personality UI and Decision Explanation

**User Story:** As an advertising operator, I want the AI hosting UI to present the personality, its allowed actions, and each decision's rationale clearly, so that I can understand and trust what the AI optimizer does.

Reconciliation: the advertising list and overview presented here regroup surfaces owned by `app-functionality-completion`; the decision data originates from the Operation_Record defined in Requirement 8 and the Personality_Policy defined in Requirement 49.

#### Acceptance Criteria

1. THE AI托管 settings drawer SHALL present grouped, collapsible sections in this order: 优化目标, 目标 ACoS, AI人格, AI可执行动作, 安全边界, 决策频率, 审批规则, 提交预览.
2. WHERE the viewport is a small (mobile) screen, THE AI托管 settings drawer SHALL render as a full-screen panel rather than a side drawer.
3. THE Frontend SHALL render AI人格 as a three-segment single-select control rather than a plain dropdown.
4. THE Frontend SHALL show, for each AI人格 segment, the core goal, the expected action frequency, the maximum adjustment magnitude, the auto keyword and negative behavior, and the approval requirement.
5. WHEN an operator selects an AI人格 segment, THE Frontend SHALL display a "人格影响预览" listing the concrete allowed actions for the selected AI_Personality; on a desktop viewport the preview MAY appear to the right of the personality selector, and WHERE the viewport is a small (mobile) screen (the drawer renders as a full-screen panel with no right side per criterion 2), THE Frontend SHALL expand the "人格影响预览" BELOW the personality selector rather than to the right.
6. THE advertising list SHALL show, by DEFAULT, exactly these visible columns: 广告活动名称, 广告类型, AI托管状态, AI人格, 目标 ACoS, 待执行操作, Amazon 同步状态, with 广告活动名称 fixed (pinned) as the first column; all other advertising columns (including 优化目标, 最近一次 AI 决策, 下次评估时间) SHALL be available via column management or the row detail drawer and SHALL NOT be shown by default.
7. THE advertising list AI人格 column SHALL show the Campaign's RESOLVED AI_Personality, with an indicator distinguishing whether the value is inherited from the Goal default or overridden at the Campaign level.
8. THE Frontend SHALL render the AI人格 column as a compact label that does not rely on color alone, using a shield icon for `conservative`, a balance/scale icon for `balanced`, and a trend icon for `aggressive`.
9. WHEN an operator clicks the AI人格 CELL of a specific Campaign row, THE Frontend SHALL open a side drawer (or full-screen panel on mobile) to view that Campaign's full personality rules, and THE Frontend SHALL NOT open the drawer from the column header and SHALL NOT allow inline editing of AI人格 within the table.
10. THE AI托管 overview first screen SHALL display only these figures: 托管活动数 (托管活动), 今日 AI 决策数 (今日决策), 等待审批数 (等待审批), Amazon 生效数 (Amazon生效), and 失败数 (失败).
11. THE AI托管 overview SHALL move 今日预算增减, the AI sales-change estimate, and the AI spend-savings estimate out of the first screen and into the trend area, and SHALL also present the "AI人格分布" breakdown showing the count of Campaigns per resolved AI_Personality.
12. THE Frontend SHALL label the AI sales-change figure as "AI 带来的销售变化（估算）" and the AI spend-savings figure as "AI 节省花费（估算）", and THE Advertising_Module SHALL compute each as an ESTIMATE using a defined estimation method per Requirement 19, never as a naive before/after delta; WHERE there is no valid baseline for either figure, THE Frontend SHALL display "暂不可估算：缺少有效基线" instead of the figure and SHALL NOT display a fabricated estimate.
13. WHEN an operator clicks a count in the "AI人格分布" breakdown, THE Frontend SHALL filter the advertising list to the Campaigns under that resolved AI_Personality.
14. WHEN the Frontend displays an AI decision explanation, THE Frontend SHALL display the trigger metric, the resolved AI_Personality used, the decision reason, the before value, the after value, the personality-allowed maximum magnitude, the actual magnitude, the predicted impact, the approval-required flag, and the Amazon sync result, for example: "平衡型人格建议将竞价从 $1.00 调整为 $1.08。原因：近7天 ACoS 18.4%，低于目标 ACoS 25%，且转化率稳定。平衡型允许单次最高上调 10%，本次上调 8%。".

## Non-Functional Requirements

### Requirement 51: Performance, Operations, and Rollout

**User Story:** As a platform operator, I want defined performance, retention, logging, alerting, migration, rollout, and browser-support targets, so that the advertising module is operable and safe at scale.

#### Acceptance Criteria

1. WHEN an operator requests a paginated advertising table over a dataset of approximately 100,000 records, THE Backend SHALL return a page within 2 seconds at the 95th percentile, measured with the following acceptance-test parameters: a page size of 50 rows, a concurrency level of 20 simultaneous requests, sorting by the `updatedAt` field descending, and a defined reference test environment of 4 vCPU / 8 GB application instance against MySQL 8.0 with 8 GB buffer pool.
2. WHEN an operator exports an advertising table whose filtered result set is at or below 10,000 rows, THE Backend SHALL produce the export synchronously.
3. WHERE an export's filtered result set exceeds 10,000 rows, THE Backend SHALL produce the export asynchronously and notify the operator when the file is ready.
4. THE Backend SHALL retain advertising operation logs for at least 365 days.
5. WHEN the Backend logs a platform request, THE Backend SHALL mask credentials and secrets so that no secret value appears in logs.
6. THE Backend SHALL emit structured logs and monitoring metrics for advertising write-back submissions, including counts of submitted, effective, and failed Operations.
7. THE Backend SHALL raise a write-back failure alert WHEN, over a rolling 15-minute window with a minimum sample of 10 write-back attempts, the write-back failure rate is at or above 25%, OR WHEN 5 or more consecutive write-back failures occur for the same Store and Write_Connector.
8. THE Backend SHALL define a database migration and rollback strategy for new advertising fields consistent with the `project-fix-and-cleanup` `schema.sql` single-source-of-truth approach.
9. THE System SHALL gate the advertising rework behind feature flags supporting a canary rollout to a subset of stores before full release.
10. THE Frontend SHALL support the current and previous two major versions of Chrome, Edge, Firefox, and Safari.
11. THE rolling window duration of 15 minutes, the minimum sample of 10 write-back attempts, the failure-rate threshold of 25%, the consecutive-failure count of 5 defined in acceptance criterion 7, the synchronous-export row threshold of 10,000, and the pagination P95 test parameters defined in acceptance criterion 1 SHALL each be configurable backend values.
12. THE Backend SHALL add a Marketplace_Timezone field to the Marketplace data (the `MarketplaceEntity`, which has a currency but no timezone today), and SHALL use that field as the source of "today", day boundaries, date ranges, and scheduling for advertising metrics.
13. WHEN the Backend or Frontend computes a "today" or a day-boundary metric, or schedules a recurring job, THE Advertising_Module SHALL compute the boundary in the Active_Store's Marketplace_Timezone.
14. IF a Store's Marketplace_Timezone is not set, THEN THE Advertising_Module SHALL reject day-boundary-dependent metric and scheduling computations with a configuration error identifying the missing Marketplace_Timezone rather than defaulting to the server timezone.

## Testing and Acceptance Requirements

### Requirement 52: Testing and Acceptance Coverage

**User Story:** As a quality owner, I want defined test coverage across contracts, isolation, state machines, write-back, migrations, and UI, so that the rework's correctness is verifiable.

#### Acceptance Criteria

1. THE test suite SHALL include front/back contract tests confirming the request and response shapes for the campaign update, keyword update, and generate-recommendations endpoints per Requirement 1.
2. THE test suite SHALL include data-isolation integration tests confirming advertising reads and writes are restricted to the caller's effective data scope per Requirements 24 and 25.
3. THE test suite SHALL include cross-store identifier privilege-escalation (越权) tests confirming that referencing another store's advertising record by identifier is rejected with HTTP 403.
4. THE test suite SHALL include Operation state-machine property tests confirming that only the legal Sync_State transitions defined in Requirement 4 are permitted.
5. THE test suite SHALL include write-back tests covering success, failure, timeout, and duplicate-callback handling per Requirements 3, 4, and 5.
6. THE test suite SHALL include ACoS historical-data migration tests confirming per-column migration and that ambiguous values are flagged for manual review per Requirement 17.
7. THE test suite SHALL include batch partial-failure tests confirming the cross-store whole-batch rejection and the in-scope per-item result format per Requirement 6.
8. THE test suite SHALL include UI permission tests confirming controls are hidden or disabled per the permission matrix in Requirement 27.
9. THE test suite SHALL include filter, pagination, and sort end-to-end tests confirming server-side application across the full result set.
10. THE test suite SHALL include a no-Write_Connector local-only indication test confirming the "仅保存在系统，Amazon 未变更" indication is shown per Requirement 12.
11. THE test suite SHALL include AI_Personality enforcement tests confirming that the AI optimizer applies the per-personality Personality_Policy magnitudes from Requirement 49 and never exceeds the Safety_Boundary, including a property test confirming that for every AI decision the actual applied magnitude is at or below both the personality-allowed maximum magnitude and the Safety_Boundary limit.
12. THE test suite SHALL include approval-state transition tests confirming that a change whose absolute change ratio is at or above (greater than or equal to) an approval threshold transitions to `awaiting_approval`, that approval advances it to `submitted` while rejection advances it to `cancelled`, and that each configured approval ratio is strictly below its corresponding maximum ratio so approval triggers before the maximum, per Requirements 4, 22, and 49.
13. THE test suite SHALL include Operation cancellation and supersession tests confirming that a NOT-yet-submitted Operation (`pending`/`awaiting_approval`) transitions DIRECTLY to `cancelled` on operator cancel and to `superseded` when replaced, that an already-submitted/in-flight Operation (`submitted`/`amazon-processing`) transitions to `cancel_requested` on operator cancel rather than directly to `cancelled`, and that the affected entity's confirmed value is unchanged, per Requirement 4.
14. THE test suite SHALL include a Personality_Rule_Version recording test confirming that each AI decision records the Personality_Rule_Version in effect on its Operation_Record, per Requirement 49.
15. THE test suite SHALL include personality-inheritance tests confirming the resolution chain that a Campaign_Personality override takes precedence over the Goal_Personality_Default, that an unset override resolves to the Goal default, that a Campaign with a null `goal_id` or a Goal with no default resolves to the Store_Default_Personality, and that an unset Store default resolves to the system fallback `balanced`, per Requirement 49.
16. THE test suite SHALL include a direct `submitted` → `effective` path test confirming an Operation that is acknowledged and confirmed without passing through `amazon-processing` reaches `effective`, per Requirement 4.
17. THE test suite SHALL include Marketplace_Timezone day-boundary edge-case tests confirming that "today" and day-boundary metrics are computed in the Active_Store's Marketplace_Timezone, including boundary cases around midnight and date-line differences, per Requirement 51.
18. THE test suite SHALL include a personality-switch recompute/supersede test confirming that when an operator changes AI_Personality and chooses to recompute, NOT-yet-submitted affected Operations are superseded directly while already-submitted/in-flight affected Operations are routed through the `cancel_requested` path, and that new Operations are created under the new AI_Personality, per Requirement 49.
19. THE test suite SHALL include an Outbox test confirming that the first transaction writes the Operation, pending-change, audit, and Outbox records without changing the entity's confirmed value or calling Amazon, and that the asynchronous worker submits outside any database transaction, per Requirement 6.
20. THE test suite SHALL include an optimistic-lock concurrency test confirming that a stale-version Operation is rejected via the version column update condition, per Requirement 5.
21. THE test suite SHALL include a `cancel_requested` resolution test confirming that an in-flight Operation moved to `cancel_requested` resolves to `cancelled` when the platform confirms the change was not applied, resolves to `effective` (with a compensating rollback Operation created) when the platform reports the change already applied, and resolves to `reconciliation_required` (NOT `failed`) when the cancellation request itself errors or cannot be confirmed, per Requirement 4.
22. THE test suite SHALL include an expired-reconciliation test confirming that `expired` is NOT terminal: that an expired Operation queries the platform's actual state before any retry, does NOT immediately retry, and transitions to `effective`, `failed`, or `reconciliation_required` according to the platform's reported state, per Requirement 4.
23. THE test suite SHALL include an operationScope separation test confirming that a `local_configuration` Operation (AI personality, Goal configuration, hosting policy/strategy, or notification configuration) creates NO Outbox entry, is never submitted to Amazon, and carries no platform Sync_State but instead an `executionStatus`, that a Saved_View change creates NO Operation_Record and NO operation-log entry at all, and that a `platform_mutation` Operation does create an Outbox entry, per Requirements 3 and 6.
24. THE test suite SHALL include an idempotency key-model test confirming that repeated operator clicks sharing a `logicalIdempotencyKey` are coalesced into one logical Operation, that a retry creates a new `attemptId`/`submissionIdempotencyKey` under the same `logicalOperationId` and is NOT blocked by the prior attempt's `submissionIdempotencyKey`, and that a re-delivered submission with an already-processed `submissionIdempotencyKey` produces no duplicate platform request, per Requirement 5.
25. THE test suite SHALL include a local-only draft publish test confirming that clicking "submit to Amazon" on a terminal `local-only` draft creates a NEW `pending` `platform_mutation` Operation that carries its own new `logicalOperationId` and a `parentOperationId` referencing the original terminal `local-only` Operation, and does NOT transition the terminal `local-only` Operation, per Requirement 12.
26. THE test suite SHALL include a connector-architecture test confirming that platform execution becomes available for a Store only when BOTH the platform's Write_Connector implementation exists AND the Store has a valid active PlatformConnection, per Requirement 53.
27. THE test suite SHALL include a `reconciliation_required` resolution test confirming that an Operation in `reconciliation_required` (reached from `expired` or from a failed/unconfirmed cancellation) resolves to exactly one of `effective`, `failed`, or `cancelled` according to the reconciled platform state, per Requirement 4.
28. THE test suite SHALL include a cancellation-error routing test confirming that when a cancellation request itself errors or cannot be confirmed, the Operation transitions to `reconciliation_required` and NOT to `failed`, per Requirement 4.
29. THE test suite SHALL include a Recommendation_Status mapping test confirming that every Unsettled_State maps to `applying`, that `expired` does NOT map to `failed`, that `cancelled` and `superseded` map to `pending` (re-actionable), and that a Recommendation with no applied Operation remains `pending`, per Requirement 10.
30. THE test suite SHALL include an undo-availability test confirming that the undo control is offered for an `effective` Operation only when `reversible = true` AND the before value is still valid, and is NOT offered once a newer `effective` Operation has changed the field, per Requirement 8.
31. THE test suite SHALL include an external-version-conflict test confirming that when an Operation becomes `effective` while the local optimistic-lock version changed during external execution, the Advertising_Module records both the platform-confirmed value and the conflicting local change and requires explicit resolution rather than overwriting last-writer-wins, per Requirement 5.
32. THE test suite SHALL include a `local_configuration` executionStatus test confirming that AI personality, Goal configuration, hosting policy/strategy, and notification configuration Operations carry an `executionStatus` of `applied`, `failed`, or `cancelled` and no platform Sync_State, that a Saved_View change creates no Operation at all, and that the UI displays the `executionStatus`, per Requirement 3.
33. THE test suite SHALL include an extended-connector-contract test confirming that the Write_Connector exposes `submit`, `queryStatus`, `requestCancel`, and `supportsCancel`, that `queryStatus` is idempotent and non-mutating, that inbound callback signatures are verified before acting, and that the stored platform reference correlates `queryStatus`, `requestCancel`, and callbacks to the correct Operation, per Requirement 55.
34. THE test suite SHALL include a state-to-actions matrix test confirming that the action buttons offered for each Sync_State match the matrix of Requirement 56 and that no illegal action is offered.
35. THE test suite SHALL include an Optimization_Goal migration test confirming that each known existing goal-type / hosting-goal value maps to one of `profit_first`, `sales_growth`, `rank`, or `clearance`, and that unmappable values are written to a migration exception list rather than auto-mapped, per Requirement 57.

## Prerequisite Milestone and Phasing Requirements

### Requirement 53: Amazon Ads Write Connector Prerequisite Milestone

**User Story:** As a platform operator, I want the Amazon Ads Write Connector treated as an explicit prerequisite milestone, so that the system is honest that platform execution is unavailable until the connector exists and operates only in local-draft / simulation mode until then.

The review verified that there is no `PlatformWriteConnector` implementation today; the only write path is the recommendation-only `WriteBackService.apply(recommendationId)`.

#### Acceptance Criteria

1. THE Advertising_Module SHALL treat the existence of the platform's `PlatformWriteConnector` (the `Write_Connector`) IMPLEMENTATION, registered ONCE for the platform (one connector implementation per platform, not one per Store), as a prerequisite milestone for all platform execution, and SHALL treat a Store as write-capable only when that implementation exists AND the Store has a valid active PlatformConnection.
2. WHILE a Store is not write-capable (the platform Write_Connector implementation is absent OR the Store has no valid active PlatformConnection), THE Advertising_Module SHALL NOT make the platform-dependent Sync_States `submitted`, `amazon-processing`, `effective`, `cancel_requested`, or `reconciliation_required` reachable for that Store and SHALL NOT process platform callbacks for that Store.
3. WHILE a Store is not write-capable, THE Advertising_Module SHALL operate in local-draft / simulation mode for that Store, persisting Operations in local-tracked states per Requirement 3.
4. WHERE a requirement depends on real platform execution, THE Advertising_Module SHALL state and honor its dependency on this prerequisite milestone rather than presenting platform execution as available before the Store is write-capable.
5. THE Advertising_Module SHALL build the generic `Operation_Write_Back` (`applyOperation(operationId)`) of Requirement 2 as net-new work that generalizes the current recommendation-only `apply(recommendationId)` path, rather than assuming a generic write path already exists.

### Requirement 54: Phased Delivery of Executable AI Hosting

**User Story:** As an advertising operator, I want executable AI hosting capabilities delivered in phases matching what the optimizer can actually do, so that the UI never presents an unimplemented capability as executable.

The review verified that the current optimizer (`AiHostingOptimizer`) adjusts only local keyword bids.

#### Acceptance Criteria

1. THE Advertising_Module SHALL deliver executable AI hosting capabilities in three phases: V1 SHALL be bid adjustment only (matching the current optimizer); V2 SHALL add budget adjustment; V3 SHALL add keyword expansion and negative-keyword addition.
2. WHERE a capability is not yet implemented for the active phase, THE Frontend SHALL NOT present that capability as executable.
3. THE Advertising_Module SHALL apply AI_Personality controls (Requirement 49) only to the capabilities implemented for the active phase.
4. WHEN the active phase advances, THE Advertising_Module SHALL enable the newly implemented capabilities as executable in the UI consistent with the supported-capability set of Requirement 22.
## Connector, State-Action, and Migration Requirements

### Requirement 55: Extended Platform Write Connector Contract

**User Story:** As a platform integrator, I want the platform write-connector contract extended beyond a bare `submit` so that status queries, cancellation, callbacks, and reconciliation are well-defined, so that the operation state machine and reconciliation rules can actually be driven by the connector.

The review verified that the current `PlatformWriteConnector` concept exposes only `submit()`. The capabilities below are a REQUIRED EXTENSION to the connector contract owned by `core-platform-completion`; they DO NOT exist today and this spec depends on them.

Reconciliation: `core-platform-completion` owns the `PlatformWriteConnector` contract. This requirement defines an agreed EXTENSION to that contract (not a redefinition of ownership). The design phase MUST reconcile the extension with the core connector contract rather than fork it.

#### Acceptance Criteria

1. THE platform Write_Connector contract SHALL be extended, as an agreed extension to the `core-platform-completion` connector contract, to support at minimum the operations `submit`, `queryStatus`, `requestCancel`, and `supportsCancel`.
2. THE extended Write_Connector contract SHALL define a `queryStatus` operation that returns the platform's current status for a previously submitted Operation and is usable for both polling and reconciliation per Requirement 4.
3. THE extended Write_Connector contract SHALL define a `requestCancel` operation that requests cancellation of an already-submitted or in-flight Operation, and a `supportsCancel` indicator that reports whether the platform supports cancellation, so that the `cancel_requested` path of Requirement 4 can branch on cancel support.
4. THE extended Write_Connector contract SHALL define a platform-status → Sync_State mapping that maps each platform-reported status to exactly one of the Sync_States defined in the Glossary, so that platform statuses, callbacks, and `queryStatus` results resolve to a single defined Sync_State.
5. WHEN the Backend receives an inbound platform callback, THE Backend SHALL verify the authenticity and signature of the callback before acting on it, and IF the callback signature cannot be verified, THEN THE Backend SHALL reject the callback and SHALL NOT mutate any Operation state from it.
6. THE `queryStatus` operation SHALL be idempotent: repeated `queryStatus` calls for the same Operation SHALL produce the same result for an unchanged platform state and SHALL never mutate platform state.
7. THE Advertising_Module SHALL store, on each `platform_mutation` Operation, the platform's own reference/identifier returned at submission, and SHALL use that platform reference to correlate `queryStatus` results, `requestCancel` requests, and inbound callbacks to the correct Operation.
8. THE Advertising_Module SHALL treat this extended connector contract as a required dependency that does not exist today rather than as an already-implemented capability, consistent with the prerequisite milestone of Requirement 53.

### Requirement 56: Operation State to Available Actions Matrix

**User Story:** As an advertising operator, I want a complete, explicit mapping of each Operation Sync_State to the action buttons available in that state, so that the UI only ever offers actions that are legal for the Operation's current state.

#### Acceptance Criteria

1. THE Frontend SHALL determine the action buttons available for an Operation from its current Sync_State using exactly the following complete matrix, where the available actions are drawn from Approve, Reject, Cancel, Retry, Reconcile, and Undo:

   | Sync_State | Available actions |
   |---|---|
   | `local-only` | none of {Approve, Reject, Cancel, Retry, Reconcile, Undo} (a "submit to Amazon" / publish control is offered instead once the Store is write-capable, per Requirement 12) |
   | `pending` | Cancel |
   | `awaiting_approval` | Approve, Reject, Cancel |
   | `submitted` | Cancel (which routes to `cancel_requested` per Requirement 4) |
   | `amazon-processing` | Cancel (which routes to `cancel_requested` per Requirement 4) |
   | `cancel_requested` | none (awaiting platform resolution per Requirement 4) |
   | `effective` | Undo (offered ONLY when the Operation's `reversible` flag is `true` AND the before value is still valid, per Requirement 8 criterion 4) |
   | `failed` | Retry |
   | `expired` | Reconcile |
   | `reconciliation_required` | Reconcile |
   | `cancelled` | none (the change may be re-created as a new Operation) |
   | `superseded` | none (the change may be re-created as a new Operation) |

2. WHERE the matrix lists no available action for a Sync_State, THE Frontend SHALL NOT present Approve, Reject, Cancel, Retry, Reconcile, or Undo for an Operation in that state.
3. THE Frontend SHALL present the Undo action for an `effective` Operation only when the conditions of Requirement 8 criterion 4 hold (`reversible = true` AND the before value is still valid), and SHALL NOT present Undo otherwise.
4. WHEN an operator activates Cancel for an Operation in `submitted` or `amazon-processing`, THE Advertising_Module SHALL route the Operation to `cancel_requested` per Requirement 4 rather than transitioning it directly to `cancelled`.
5. WHEN an operator activates Reconcile for an Operation in `expired` or `reconciliation_required`, THE Advertising_Module SHALL query the platform's actual state and resolve the Operation per Requirement 4 rather than blindly retrying.

### Requirement 57: Optimization Goal Enum Migration Mapping

**User Story:** As an implementer, I want the existing goal-type / hosting-goal enums explicitly mapped to the new Optimization_Goal machine enums, so that the rework does not assume the new enums already exist in the current system.

The review verified that the current system does not already use the machine enums `profit_first`, `sales_growth`, `rank`, and `clearance`; the existing goal-type / hosting-goal values MUST be mapped to them.

#### Acceptance Criteria

1. THE Advertising_Module SHALL define an explicit migration mapping from each existing goal-type / hosting-goal enum value in the current system to exactly one of the new Optimization_Goal machine enums `profit_first`, `sales_growth`, `rank`, or `clearance`, rather than assuming the new enums already exist.
2. THE Backend SHALL apply the migration mapping to existing persisted goal-type / hosting-goal values so that every known existing value is normalized to its mapped Optimization_Goal machine enum.
3. IF an existing goal-type / hosting-goal value does not map to any of the four new Optimization_Goal machine enums, THEN THE Backend SHALL write the value to a migration exception list for manual review and SHALL NOT auto-map it to an arbitrary Optimization_Goal value.
4. THE Backend SHALL return only the new Optimization_Goal machine enum values for the migrated field, consistent with the Machine_Value_Enum contract of Requirement 48.
