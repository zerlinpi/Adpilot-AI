# Implementation Plan: App Functionality Completion

## Overview

This plan completes the user-facing functionality of AdPilot AI and folds the SparkX / Xnurta AI advertising logic into the platform's existing modules. It is built directly from the design's **Requirement → Module Mapping & Classification** table and follows the five-phase delivery plan (Phase 0 foundation → Phase 1 RBAC/skeleton wiring → Phase 2 core AI advertising → Phase 3 work-items/advanced → Phase 4 Insight Agent/data surfaces).

The work is mostly **wiring and fixing** existing endpoints plus **additive** schema/controllers for the missing AI advertising surfaces. Backend is Java 17 / Spring Boot 3.2.5 (MyBatis-Plus + JPA + Flyway + MySQL 8.0); frontend is React/TypeScript/Vite. Property-based tests use **jqwik** (backend) and **fast-check** (frontend), each running a minimum of 100 iterations and tagged `Feature: app-functionality-completion, Property {number}: {property_text}`.

All new schema is additive only (`V2`, `V3`, `V4` migrations); `V1__init_schema.sql` is never edited.

## Tasks

- [x] 1. Phase 0 — JSON error contract (Req 1)
  - [x] 1.1 Sweep controllers returning `ApiResponse.fail(...)` at HTTP 200
    - Remove broad `try/catch → fail()` at 200 in `CampaignController`, `GoalController`, `KeywordController`, `RecommendationController`, `SearchTermController`
    - Throw `BusinessException` (carries code + status) for domain errors; use the `fail(int httpStatus, ...)` overload so failures carry 4xx/5xx
    - Verify `GlobalExceptionHandler` sets correct status for `BusinessException`, validation, and generic exceptions
    - _Requirements: 1.1, 1.2, 1.3_

  - [x] 1.2 Add `ApiNotFoundController` for undefined `/api/**` routes
    - Lowest-priority `@RequestMapping("/api/**")` handler returning 404 JSON `{success:false,error:{code:"NOT_FOUND",message}}`
    - _Requirements: 1.5_

  - [x] 1.3 Add SPA fallback and harden `JwtAuthFilter`
    - Add forwarding `@Controller`/`WebMvcConfigurer` view controller that forwards unmatched non-`/api` paths to `index.html`, scoped so it never intercepts `/api/**`
    - Wrap `JwtAuthFilter.doFilter` body in `try/catch` that serializes a JSON error via the injected `ObjectMapper` (mirrors `SecurityConfig` entry/deny-point JSON)
    - _Requirements: 1.3_

  - [x] 1.4 Harden frontend `request()` helper in `lib/api.ts`
    - When `res.json()` throws on a non-JSON body, construct a readable error from `res.status` + `res.statusText` instead of surfacing the raw parse error; never display raw parse text
    - _Requirements: 1.4_

  - [x] 1.5 Write property test for response envelope contract (backend, jqwik)
    - **Property 1: Response envelope contract** — for any success payload or thrown error/undefined route, success sets `success=true` with payload under `data`; failure sets `success=false` with non-empty `error.code` and `error.message` of length 1–500, with 4xx/5xx status
    - **Validates: Requirements 1.1, 1.2, 1.3, 1.5**

  - [x] 1.6 Write property test for frontend readable error (frontend, fast-check)
    - **Property 2: Frontend renders a readable error for any non-JSON body** — for any HTTP status and any non-JSON body, `request()` produces an error message including the HTTP status and a fallback description, never the raw parse text
    - **Validates: Requirements 1.4**

  - [x] 1.7 Write integration tests for servlet-level JSON error paths
    - JSON-on-404 (undefined `/api` route) and JSON-on-filter-error (`JwtAuthFilter` throw) return JSON, not HTML
    - _Requirements: 1.3, 1.5_

- [x] 2. Phase 0 — Navigation restructure and localization (Req 16, 17)
  - [x] 2.1 Restructure `navSections` in `Layout.tsx` to the SparkX taxonomy
    - Map items to 首页, 自定义看板, AI广告优化, Insight Agent, 高阶广告设置, 效率工具, 数据洞察, AMC数据工作室, 帮助中心 per the design taxonomy; each item under exactly one section
    - Keep permission gating and empty-section omission in `navVisibility.ts`; wire item routes
    - _Requirements: 16.1, 16.2, 16.4, 16.5_

  - [x] 2.2 Complete the Chinese Localization_Catalog and route new strings through `t()`
    - Add missing strings to `i18n/{menu,pages,forms,enums,errors,actions,toast}.ts`; replace hardcoded literals with `t()` lookups
    - Surface missing-key lookups during development
    - _Requirements: 17.1, 17.2, 17.3, 17.4_

  - [x] 2.3 Write property test for navigation visibility invariant (frontend, fast-check)
    - **Property 12: Navigation visibility structural invariant** — for any nav config and permission set, each permitted item appears under exactly one section, and any section with no visible items is omitted (extend existing `navVisibility.property.test.ts`)
    - **Validates: Requirements 16.2, 16.3**

- [x] 3. Checkpoint - Ensure all Phase 0 tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Phase 1 — RBAC pages (Req 3, 4, 5)
  - [x] 4.1 Wire Role permission editor (`RolesPage`)
    - Load `GET /api/roles/{id}/permissions` (full set + assigned subset), render editable selector, save via `POST`, show success + updated assignment; enforce `role:manage` on backend
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

  - [x] 4.2 Wire Permission catalog page (`PermissionsPage`)
    - Render `GET /api/permissions` grouped by category with id + description, loading/empty/error states; verify the `permissions` seed is non-empty
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6_

  - [x] 4.3 Implement user-validation helper and fix user creation (`UsersPage`/`UserManagement`)
    - Extract a pure `UserValidation` helper (name 1–255 chars; email single "@", non-empty local/domain, ≤320 chars; case-insensitive email dedup)
    - Fix create-user payload + FE field-level validation + optimistic list update; backend enforces `user:manage`, rejects duplicate email, returns created record with id
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6_

  - [x] 4.4 Write property test for user input validation and email dedup (backend, jqwik)
    - **Property 10: User input validation with case-insensitive email dedup** — accept iff name length 1–255 and email syntactically valid (single "@", non-empty parts, ≤320); reject an email differing from an existing user's only by case
    - **Validates: Requirements 5.1, 5.3, 5.4**

- [x] 5. Phase 1 — Skeleton-page data wiring, Listing AI, Product Upload (Req 6, 7, 8)
  - [x] 5.1 Wire all Skeleton_Pages to their existing backing endpoints
    - For each listed page (今日待办, 审批中心, 广告目标, 广告活动, AI优化建议, 产品列表, 库存健康, 补货, 仓库, 采购, 供应商, FBA货件, 利润看板, SKU利润, 结算, Review, Feedback, 全部任务, 报表中心): request store-scoped data, render records, add empty-state and error-with-retry; reflect Primary_Action results without full reload
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6_

  - [x] 5.2 Fix Listing AI product-id resolution (`ListingAIPage`)
    - Resolve and pass a valid product UUID (route param / selected product) to generate/score/compliance-check; prompt to select a product when none resolvable instead of "ASIN: N/A"; render returned results
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_

  - [x] 5.3 Fix product upload navigation (`ProductUploadPage` entry)
    - Navigate to `/product-upload` carrying store/product context; render the upload workflow on arrival
    - _Requirements: 8.1, 8.2, 8.3_

  - [x] 5.4 Write unit tests for Listing AI resolution and upload navigation
    - Product-id resolution and "select a product" prompt; navigation target and context carry
    - _Requirements: 7.6, 8.1, 8.2_

- [x] 6. Phase 1 — Audit, Data Quality, Login logs (Req 11, 12, 15)
  - [x] 6.1 Implement total audit code-to-label mapping and filters (`AuditRollbackPage`)
    - Enrich `AuditLogVo` (or FE map) so action/entity-type codes render readable labels and unrecognized codes still yield a non-empty label; show entity id + source; wire action and entity-type filters to backend
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6_

  - [x] 6.2 Write property test for audit label mapping totality (backend, jqwik)
    - **Property 11: Audit code-to-label mapping is total** — for any action/entity-type code including unrecognized ones, the mapping returns a non-empty label and never renders a recognized code raw
    - **Validates: Requirements 11.1, 11.2**

  - [x] 6.3 Fix Data Quality page reliability (`DataQualityPage`)
    - Fix list endpoint + empty state; confirm resolve/ignore record the acting user via `SecurityUtils`; render check results
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5_

  - [x] 6.4 Fix Login log display (`LoginLogsPage`)
    - Confirm `GET /api/login-logs` returns data, login attempts are recorded, page renders user/status/source/timestamp, status filter wired, empty-state view
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.5_

- [x] 7. Phase 1 — Platform connections, Store relationship, Feishu (Req 13, 14, 10)
  - [x] 7.1 Make platform connections dynamic (`ApiConnectionsPage`)
    - Render `GET /api/platform-connections` list; build add-connection form from `GET /{platform}/fields`; allow many connections per platform; wire create/test/disconnect
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5, 13.6, 13.7_

  - [x] 7.2 Surface Store ↔ Platform_Connection relationship (`StoresPage`)
    - Show store-scoped connections with platform + status, add-from-store-context via `POST /api/stores/{id}/bind`, present store vs connection as distinct concepts, indicate unassigned connections
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5_

  - [x] 7.3 Add Feishu chat-binding and notification-rule endpoints
    - `POST/GET /api/integrations/feishu/{id}/chat-bindings` (backed by existing `feishu_chat_bindings`); `GET/POST /api/integrations/feishu/{id}/notification-rules` (additive `feishu_notification_rules`); enforce `feishu:manage`
    - _Requirements: 10.2, 10.4, 10.6_

  - [x] 7.4 Replace Feishu page stub with real binding/rule UI (`FeishuIntegrationPage`)
    - Remove `ErpFeatureUnderConstruction` + `setTimeout` save; render working binding + rule-config interfaces calling the backend; show actual save result
    - _Requirements: 10.1, 10.3, 10.5_

- [x] 8. Checkpoint - Ensure all Phase 1 tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 9. Phase 2 — Schema migration and AI advertising dashboard (Req 18)
  - [x] 9.1 Create `V2__ai_advertising_module.sql` migration
    - Additive: campaign hosting columns (`hosting_enabled`, `hosting_goal`, `target_acos`, `ai_managed`, `portfolio_id`) + index; `ad_portfolios` table + index
    - _Requirements: 20.1, 21.1_

  - [x] 9.2 Implement pure `AdMetrics` helper (ACoS / TACoS / AI coverage)
    - Pure functions: ACoS = spend/adSales, TACoS = spend/totalSales (as %), non-negative, zero-denominator sentinel (no NaN/exception), AI coverage clamped to [0,100]
    - _Requirements: 18.1, 18.3, 18.4_

  - [x] 9.3 Write property test for ad-metric computation (backend, jqwik)
    - **Property 3: Ad-metric computation (ACoS / TACoS / AI coverage)** — for any non-negative spend/adSales/totalSales, ACoS/TACoS correct and non-negative, zero denominator yields sentinel, coverage in [0,100]
    - **Validates: Requirements 18.1, 18.3, 18.4**

  - [x] 9.4 Add dashboard aggregation endpoints
    - `GET /api/dashboard/{sales-overview,sales-trend,ai-actions,ai-usage,ai-notifications-summary}`, store/marketplace/currency/date-range scoped; aggregate `performance_daily`, `automation_executions`, `ai_notifications`; use `AdMetrics`
    - _Requirements: 18.1, 18.3, 18.4, 18.5_

  - [x] 9.5 Render the AI dashboard panels (`DashboardPage`/`CommandCenterPage`)
    - Sales Overview metrics + deltas, dual-axis trend chart with day/week/month toggle, AI Actions panel, AI Usage panel, AI Notifications summary; re-request on top-bar date/marketplace/currency change; per-panel empty/error-with-retry
    - _Requirements: 18.1, 18.2, 18.6, 18.7, 18.8_

- [x] 10. Phase 2 — All Search Ads workspace and Ad Portfolios (Req 19, 20)
  - [x] 10.1 Implement `CampaignFilter` predicate and extend campaign list filters
    - Pure store-scoped filter predicate over `adType`, `portfolioId`, `parentAsin`, `targetingGoal`, `status`, `targetAcosMin/Max`, `smartFilter`; extend `GET /api/campaigns`
    - _Requirements: 19.4_

  - [x] 10.2 Write property test for filter soundness and completeness (backend, jqwik)
    - **Property 6: Filter soundness and completeness** — for any dataset and any active filter combination, the result contains every item satisfying all filters and none that fail any, scoped to the active store (covers campaigns and creative assets)
    - **Validates: Requirements 19.4, 29.3**

  - [x] 10.3 Add campaign create/state/bulk/trend endpoints
    - `POST /api/campaigns` (create), `PATCH /api/campaigns/{id}/state` (JSON body enable/pause), `POST /api/campaigns/bulk` (per-item result), `GET /api/campaigns/trend` (from `performance_daily`)
    - _Requirements: 19.5, 19.6, 19.7_

  - [x] 10.4 Build the tabbed All Search Ads workspace (`CampaignsPage`)
    - Tabs (广告活动/AI托管/广告组/推广商品/投放/否定投放/搜索词/购买的其他商品/竞价调整/SP预算上限/操作日志); data-trend panel; campaign table columns + enable/pause toggle; filters; create workflow; bulk op; empty-state; reuse `KeywordsPage`/`SearchTermsPage` for sub-tabs
    - _Requirements: 19.1, 19.2, 19.3, 19.5, 19.6, 19.7, 19.8_

  - [x] 10.5 Implement Ad Portfolios endpoint and page
    - `AdPortfolioController` `GET/POST/PUT /api/ad-portfolios`; campaigns reference via `portfolio_id`; rollup metrics aggregate member campaigns; render portfolio list, create workflow, 无预算上限 label, empty-state
    - _Requirements: 20.1, 20.2, 20.3, 20.4_

- [x] 11. Phase 2 — AI Hosting and Automation Rule Templates (Req 21, 25)
  - [x] 11.1 Implement pure `HostingBidOptimizer` clamp logic
    - Adjusted bid stays within `[minBid, maxBid]` and within ±maxPct of current bid; moves toward Target_ACoS (no increase when ACoS>target, no decrease when ACoS<target); fixpoint when ACoS==target; shared by placement-lock clamp
    - _Requirements: 21.2, 26.3_

  - [x] 11.2 Write property test for AI-hosting bid adjustment (backend, jqwik)
    - **Property 4: AI-hosting bid adjustment moves toward Target ACoS within bounds** — clamped within range and ±maxPct, correct direction toward target, no-op at equality; same clamp keeps placement-lock bids within `[bid_min, bid_max]`
    - **Validates: Requirements 21.2, 26.3**

  - [x] 11.3 Add AI Hosting endpoints and scheduled optimizer
    - `PUT /api/campaigns/{id}/hosting` (requires Target_ACoS — validate), `DELETE /api/campaigns/{id}/hosting`; `AiHostingOptimizer` `@Scheduled` bean computes recent ACoS from `performance_daily`, emits clamped adjustments bounded by `automation_policies`, writes `campaigns`/`keywords`/`bid_changes` + `automation_executions`; per-campaign failures isolated
    - _Requirements: 21.1, 21.2, 21.4, 21.5_

  - [x] 11.4 Wire AI Hosting UI into the campaign workspace
    - AI托管 tab/row controls: assign Hosting_Goal + Target_ACoS, FE validation requiring Target_ACoS, display AI入格 indicator + Hosting_Goal + Target_ACoS, un-host action
    - _Requirements: 21.3, 21.6_

  - [x] 11.5 Implement `RuleConditionEvaluator` and rule-template endpoints
    - Pure condition→action evaluator (apply action exactly when condition true, never when false); `AutomationRuleTemplateController` `GET/POST/PUT /api/automation/rule-templates`, `/bulk`, `/{id}/links`; backed by `automation_rule_templates` + `automation_rule_template_links`; `RuleTemplateEvaluator` runs on scheduler cadence; enforce `automation:manage`
    - _Requirements: 25.1, 25.2, 25.3, 25.4, 25.6_

  - [x] 11.6 Write property test for condition-to-action rule evaluation (backend, jqwik)
    - **Property 5: Condition-to-action rule evaluation is sound** — action applied exactly when condition true for the object's metrics, never when false
    - **Validates: Requirements 25.3**

  - [x] 11.7 Wire Automation Rules / rule-template UI (`AutomationRulesPage`)
    - Render templates with name/type/store/linked-object count/status; create-template form with FE validation; bulk operation; map create-rule (Req 9) onto the template endpoint
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 25.1, 25.2, 25.4, 25.5_

- [x] 12. Checkpoint - Ensure all Phase 2 tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 13. Phase 3 — Migrations, Smart Diagnosis, AI Notifications (Req 22, 23)
  - [x] 13.1 Create `V3__ai_workitems.sql` migration
    - Additive: `ai_notifications` (+ generated `open_dedup_key` unique), `ai_notification_config`, `smart_diagnosis_tasks`, `ad_placement_lock_strategies`, `ad_placement_lock_tasks`
    - _Requirements: 22.1, 23.1, 26.2_

  - [x] 13.2 Implement Smart Diagnosis controller and page
    - `SmartDiagnosisController` `POST/GET /api/smart-diagnosis/tasks`, `GET /{id}`; diagnosis result reuses `RecommendationEngineService` over the product's ad structure; render tasks with parent ASIN/frequency/creator/last-diagnosis + result; empty-state
    - _Requirements: 22.1, 22.2, 22.3, 22.4_

  - [x] 13.3 Implement AI Notification state machine and dedup logic
    - Pure transition: pending → closed with resolution (applied/confirmed/rejected); idempotent/terminal once closed; dedup via generated `open_dedup_key` (store, category, subject)
    - _Requirements: 23.1, 23.3, 23.4_

  - [x] 13.4 Write property test for notification pending-to-closed transition (backend, jqwik)
    - **Property 7: AI notification pending-to-closed transition is idempotent and terminal** — apply/confirm/reject closes with the matching resolution; repeating on a closed item has no further effect
    - **Validates: Requirements 23.3, 23.4**

  - [x] 13.5 Write property test for notification dedup uniqueness (backend, jqwik)
    - **Property 8: AI notification dedup uniqueness** — at most one pending notification per (store, category, subject) open key at any time
    - **Validates: Requirements 23.1, 23.3**

  - [x] 13.6 Add AI Notification endpoints and page
    - `AiNotificationController` `GET /api/ai-notifications`, `POST /{id}/{apply,confirm,reject}`, `GET/PUT /api/ai-notifications/config`; render four categories with pending/closed counts, pending/closed lists, apply/confirm/reject actions, config view, empty-state
    - _Requirements: 23.1, 23.2, 23.3, 23.4, 23.5, 23.6_

- [x] 14. Phase 3 — Placement Lock, Keyword Library, Rank Monitoring (Req 26, 27, 28)
  - [x] 14.1 Implement Ad Placement Lock endpoints, enforcement, and page
    - `AdPlacementLockController` `GET/POST /api/placement-locks` (validate min ≤ max), `GET /api/placement-locks/tasks`, `GET /api/placement-locks/ams` (stubbed from stored data); enforcement in scheduled evaluator using the shared bid clamp; render 策略管理/任务管理/AMS实时数据 tabs + add-strategy with placement + bid range
    - _Requirements: 26.1, 26.2, 26.3, 26.4_

  - [x] 14.2 Create `V4__keyword_rank_creative.sql` migration
    - Additive: `keyword_libraries`, `keyword_library_items`, `rank_monitor_tasks`, `rank_monitor_snapshots`, `creative_assets`
    - _Requirements: 27.2, 28.1, 29.2_

  - [x] 14.3 Implement Keyword Library endpoints and Keywords page tabs
    - `KeywordLibraryController` `GET/POST /api/keyword-libraries`, `POST /api/keyword-libraries/recommendations/{id}/{harvest,negate}` (reuse harvest/negative flows); render 词库 + 关键词推荐 tabs, library rows (associated products/keyword count/type/last+next run), create-library form, recommendation harvest/negate actions, empty-state
    - _Requirements: 27.1, 27.2, 27.3, 27.4, 27.5_

  - [x] 14.4 Implement Rank Monitoring quota check, endpoints, and page
    - Pure quota check (permit add iff consumed < total); `RankMonitorController` `POST /api/rank-monitor/tasks` (quota-checked), `GET /api/rank-monitor/tasks`, `GET /api/rank-monitor/quota`; render organic + ad rank per keyword, consumed/total quota, quota-exhausted message blocking submit
    - _Requirements: 28.1, 28.2, 28.3, 28.4_

  - [x] 14.5 Write property test for rank-monitor quota boundary (backend, jqwik)
    - **Property 9: Rank-monitor quota boundary** — adding a task permitted iff consumed < total; at consumed == total any further add is rejected
    - **Validates: Requirements 28.4**

- [x] 15. Checkpoint - Ensure all Phase 3 tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 16. Phase 4 — Insight Agent, Creative Assets, Data Insights (Req 24, 29, 30)
  - [x] 16.1 Implement Insight Agent endpoints and page
    - `InsightAgentController` `POST /api/insight-agent/query` (store-scoped, `source`/`premium` flags), `GET /api/insight-agent/suggestions`; uses `AiClient` when enabled, deterministic stub otherwise; render suggested prompts, source selector + Premium toggle, error-with-resubmit
    - _Requirements: 24.1, 24.2, 24.3, 24.4_

  - [x] 16.2 Implement Creative Asset Library endpoints and page
    - `CreativeAssetController` `GET /api/creative-assets` (search by name/tag/ASIN/creator), `POST /api/creative-assets` (multipart via `FileStorageUtils`); render assets with type, upload, search results, empty-state (reuse `CampaignFilter`-style predicate covered by Property 6)
    - _Requirements: 29.1, 29.2, 29.3, 29.4_

  - [x] 16.3 Implement Data Insights / SQP / AMC surfaces (UI + stubbed services)
    - `GET /api/insights/{product-list,brand-metrics,market-insights,sqp}`, `GET /api/insights/amc/{models,audiences}`; product list reuses `products` + `performance_daily`; compute from stored data where available, otherwise explicit "requires activation"/empty payload; render product list metrics, custom-report quota, brand/market/SQP funnel metrics, AMC templates, activation gating, empty-states
    - _Requirements: 30.1, 30.2, 30.3, 30.4, 30.5, 30.6, 30.7, 30.8_

  - [x] 16.4 Write unit tests for stubbed-surface gating and budget-cap label
    - AMC activation gating (Req 30.7), portfolio 无预算上限 label (Req 20.3), Data Insights empty-states (Req 30.8)
    - _Requirements: 20.3, 30.7, 30.8_

- [x] 17. Phase 4 — Integration wiring and final verification
  - [x] 17.1 Write integration tests for authorization guards and scheduled optimizers
    - Guards (`user:manage`, `automation:manage`, `feishu:manage`, `advertising:manage`); `AiHostingOptimizer`/`RuleTemplateEvaluator` end-to-end against test DB; platform-connection test/disconnect; Feishu binding persistence; login-attempt recording
    - _Requirements: 3.5, 5.5, 9.4, 10.6, 25.6, 21.2, 25.3_

- [x] 18. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional (property tests, unit tests, integration tests) and can be skipped for a faster MVP; core implementation tasks are never optional.
- Each task references specific requirements (granular sub-clauses) for traceability.
- The 12 correctness properties each have a single dedicated property-based test, placed close to the pure helper it validates so errors surface early: Property 1/2 (Phase 0), Property 12 (Phase 0), Property 10/11 (Phase 1), Property 3/6/4/5 (Phase 2), Property 7/8/9 (Phase 3). Property 6 also covers creative-asset filtering (Phase 4).
- Property tests run a minimum of 100 iterations and are tagged `Feature: app-functionality-completion, Property {number}: {property_text}`.
- All new schema is additive (`V2`, `V3`, `V4`); `V1__init_schema.sql` is never edited.
- Live Amazon Ads actuation is out of scope — represented by a stubbed `PlatformConnector` seam writing to the project's own tables.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3", "2.1", "2.2"] },
    { "id": 1, "tasks": ["1.4", "1.5", "1.7", "2.3"] },
    { "id": 2, "tasks": ["1.6", "4.1", "4.2", "4.3", "6.1", "6.3", "6.4", "7.1", "7.3"] },
    { "id": 3, "tasks": ["4.4", "5.1", "5.2", "5.3", "6.2", "7.2", "7.4"] },
    { "id": 4, "tasks": ["5.4", "9.1"] },
    { "id": 5, "tasks": ["9.2", "10.1", "11.1"] },
    { "id": 6, "tasks": ["9.3", "9.4", "10.2", "10.3", "11.2", "11.5"] },
    { "id": 7, "tasks": ["9.5", "10.5", "11.3", "11.6"] },
    { "id": 8, "tasks": ["10.4", "11.4", "11.7", "13.1"] },
    { "id": 9, "tasks": ["13.2", "13.3", "14.1", "14.2"] },
    { "id": 10, "tasks": ["13.4", "13.5", "13.6", "14.3", "14.4"] },
    { "id": 11, "tasks": ["14.5", "16.1", "16.2", "16.3"] },
    { "id": 12, "tasks": ["16.4", "17.1"] }
  ]
}
```
