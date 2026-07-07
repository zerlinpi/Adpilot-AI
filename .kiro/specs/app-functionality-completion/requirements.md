# Requirements Document

## Introduction

AdPilot AI is a cross-border e-commerce intelligent operations platform with a React/TypeScript/Vite frontend (`frontend/`) and a Java 17 / Spring Boot 3.2.5 backend (`backend-java/`, using MyBatis-Plus + JPA + Flyway + MySQL 8.0 + Redis). The backend now builds and the database schema is consolidated into a single Flyway `V1__init_schema.sql` migration. Infrastructure repair, build fixes, and schema consolidation are already covered by the completed `project-fix-and-cleanup` and `core-platform-completion` specs and are explicitly out of scope here.

This spec covers completing and fixing the **user-facing functionality** that is currently half-built. Investigation of the live code confirms that most backend controllers and endpoints already exist (for example `KeywordIntelligenceController`, `ListingController`, `DataQualityController`, `AutomationController`, `FeishuController`, `UserController`, `RoleController`, `PermissionController`, `LoginLogController`, `AuditLogController`, `StoreController`, and `ApiSyncController`). The reported defects therefore fall into three categories, which the design phase MUST confirm per area:

- **Present-but-broken**: the endpoint exists but returns an error (e.g. a 500 surfacing as an HTML error page), returns empty/placeholder data, or returns data the frontend cannot render.
- **Frontend-stub**: the page renders a static "under construction" placeholder or hardcoded data instead of calling the existing endpoint (e.g. `FeishuIntegrationPage` uses a placeholder save, `ApiConnectionsPage` uses a fixed list of five platforms).
- **Missing**: no backend endpoint exists yet and one must be created.

Each requirement below notes, where known, which category applies and what must be confirmed in design.

This spec was extended with a large additional scope (Requirement 18 onward) that establishes the **AI advertising module standard** modeled on the SparkX / Xnurta platform (`ai.sparkx.io`, the SparkX_Reference). The user directive is that the AI advertising module must follow SparkX's logic as the authoritative standard for this round of changes ("AI投广告模块按照 ai.sparkx.io 这个平台的逻辑来"). Because this scope is modeled on an external reference platform, the design phase MUST map each SparkX concept onto the project's existing modules (advertising, automation, keyword, recommendation, dashboard, report) and classify each mapped surface as present, present-but-broken, or missing, rather than assuming a greenfield build. Requirement 16 has been updated to adopt the SparkX menu taxonomy as the corrected grouping reference.

The single shared frontend API client (`frontend/src/app/lib/api.ts`) wraps every call in a `request()` helper that calls `res.json()`. When an endpoint returns an HTML error page instead of JSON, `res.json()` throws `Unexpected token '<', "<html>..." is not valid JSON`, which is the exact error reported on the Keyword Intelligence page. This makes a consistent JSON error contract a cross-cutting concern (Requirement 1).

## Glossary

- **System**: The AdPilot AI application as a whole (frontend + backend) unless a more specific component is named.
- **Backend**: The Spring Boot service exposing REST endpoints under the `/api` prefix.
- **Frontend**: The React single-page application that consumes the Backend.
- **API_Client**: The shared frontend HTTP wrapper in `frontend/src/app/lib/api.ts` that attaches auth and parses JSON responses.
- **Store**: A selling entity (a seller's presence in one marketplace). Stores are listed and managed via `StoreController` (`/api/stores`). A Store is the primary data-scoping unit; most pages filter their data by the active Store.
- **Platform_Connection**: A credential channel that authenticates the System to one external platform (e.g. Amazon Ads, Amazon SP-API, Shopify, WooCommerce, TikTok Shop) on behalf of a Store. Managed via `ApiSyncController` (`/api/platform-connections`). The `seller_account_id` column already exists on the connection. One Store MAY have many Platform_Connections.
- **Platform**: An external service type a Platform_Connection targets (identified by a platform key such as `amazon_ads`, `amazon_spapi`, `shopify`, `woocommerce`, `tiktok_shop`).
- **RBAC**: Role-Based Access Control — the subsystem of Users, Roles, Permissions, and Data_Scopes governing what each user may see and do.
- **Permission**: A named capability string (e.g. `keyword:view`, `role:manage`) gating navigation items and actions, defined by `PermissionController` (`/api/permissions`).
- **Role**: A named set of Permissions assignable to Users, managed by `RoleController` (`/api/roles`).
- **Audit_Log**: A record of a security- or data-relevant action, stored by `AuditLogEntity` with fields `action`, `entityType`, `entityId`, `source`, `ipAddress`, `userId`, `createdAt`, and surfaced via `AuditLogVo`.
- **Login_Log**: A record of an authentication attempt (success or failure), surfaced via `LoginLogController` (`/api/login-logs`).
- **Skeleton_Page**: A frontend page that currently renders its UI shell (headers, tables, cards) but does not load real data from a Backend endpoint and/or whose primary actions do not perform their function.
- **Primary_Action**: The main create/update/run/apply action a page exposes (for example "新增规则" on Automation Rules, "生成 Listing" on Listing AI, "运行分析" on Keyword Intelligence).
- **Active_Store**: The Store currently selected in the header store switcher, used to scope page data.
- **Localization_Catalog**: The Chinese-text catalog under `frontend/src/app/i18n/` (`menu.ts`, `pages.ts`, `forms.ts`, `enums.ts`, `errors.ts`, `actions.ts`, `toast.ts`) accessed via the `t()` helper.
- **Nav_Section**: A labeled group of navigation items in the sidebar, defined by `navSections` in `frontend/src/app/components/Layout.tsx`.
- **SparkX_Reference**: The SparkX / Xnurta AI advertising platform (`ai.sparkx.io`) whose page logic and menu taxonomy serve as the authoritative reference standard for the AI advertising module in this round of changes. Requirements 18 and later encode SparkX_Reference behavior. Design MUST map each SparkX concept onto the project's existing modules and classify each as present, present-but-broken, or missing.
- **AI_Advertising_Module**: The set of Backend services and Frontend pages that implement AI-driven Amazon advertising optimization, modeled on the SparkX_Reference. It spans the advertising, automation, keyword, recommendation, dashboard, and report modules of this project.
- **AI_Hosting**: A managed-optimization mode in which a Campaign is placed under continuous AI control by assigning it a Hosting_Goal and a Target_ACoS; the AI_Advertising_Module then automatically adjusts bids, budget, keywords, and negative keywords toward the Target_ACoS. Maps primarily to the advertising and automation modules.
- **Hosting_Goal**: The optimization objective (托管目标) assigned to a Campaign placed under AI_Hosting (for example, maximize sales at a target efficiency).
- **Target_ACoS**: The target Advertising Cost of Sales (目标ACOS), expressed as a percentage, that AI_Hosting optimizes a Campaign toward.
- **ACoS**: Advertising Cost of Sales — advertising spend divided by advertising sales, expressed as a percentage.
- **TACoS**: Total Advertising Cost of Sales — advertising spend divided by total sales, expressed as a percentage.
- **Campaign**: An Amazon advertising campaign (广告活动) of an ad type (SP Sponsored Products, SB Sponsored Brands, or SD Sponsored Display) belonging to a Store. Managed via the advertising module.
- **Ad_Portfolio**: A grouping of Campaigns (广告组合) with shared budget and reporting, belonging to a Store.
- **AI_Action**: A discrete automated optimization the AI_Advertising_Module performs and tracks with a count and a measured impact. The standard set for SP comprises: keyword harvesting (关键词收割), negative keywords (关键词否定), bid optimization (竞价优化), budget optimization (预算优化), ad-structure optimization (广告结构优化), dayparting budget (分时段预算), dayparting bid (分时竞价), and quick-search test (快搜测试).
- **AI_Notification**: A work item (AI通知) the AI_Advertising_Module raises for operator attention, belonging to one of four categories: core ops attention (广告运营核心关注), one-click campaign optimization (广告活动一键优化), discover high-potential campaigns (发现高潜广告活动), and AI target correction pending confirmation (AI目标修正待确认). Each AI_Notification is in a pending (待处理) or closed (已结束) state.
- **Smart_Diagnosis**: A diagnosis task (诊断任务) run per product (parent ASIN) that analyzes ad structure and produces a diagnosis result, with an update frequency, a creator, and a last-diagnosis time.
- **Ad_Placement_Lock**: A placement strategy (卡位策略 / 广告位锁定) that targets a specific Amazon ad placement (for example, top-of-page 首页1-1位) for an SP Campaign using a keyword bid range, to hold the ad in that placement.
- **Insight_Agent**: A conversational AI analyst page (Insight Agent) where an operator submits a data query or analysis request and receives insights and recommended next actions, with selectable analysis sources and a Premium mode.
- **Keyword_Library**: A managed collection of keywords (词库) associated with products, with a library type and an execution schedule, that feeds keyword harvesting and negation AI_Actions. Maps to the keyword module.
- **SQP**: Search Query Performance (SQP分析) — a Data Insights surface comparing a brand's funnel metrics (click rate, add-to-cart rate, conversion rate, impressions, impression share) against the market for search queries.
- **AMC**: Amazon Marketing Cloud (AMC数据工作室) — a Data Insights workspace offering analytical model templates (AMC模型库) and audience-creation templates (用户受众创建); some AMC features are gated behind account activation.

## Requirements

### Requirement 1: Consistent JSON Error Contract

**User Story:** As an operator, I want every API call to return structured JSON even when it fails, so that pages show a readable error message instead of crashing with "Unexpected token '<'".

The reported error `Unexpected token '<', "<html>..." is not valid JSON` occurs because an endpoint returns an HTML error page that the API_Client cannot parse. Design MUST confirm, per failing endpoint, whether the cause is an unhandled server exception (500) or a routing/proxy fallback returning `index.html`.

#### Acceptance Criteria

1. WHEN any `/api` endpoint completes successfully, THE Backend SHALL return a response with a JSON content type and a body containing a `success` field set to `true` and the response payload in a `data` field.
2. IF any `/api` endpoint encounters an error, THEN THE Backend SHALL return a response with a JSON content type and a body containing `success` set to `false` and an `error` object with a non-empty string `code` and a human-readable `message` of 1 to 500 characters, and SHALL set an HTTP status code in the 4xx range for client errors or the 5xx range for server errors.
3. IF an `/api` endpoint throws an unhandled server exception, THEN THE Backend SHALL return a response with a JSON content type containing `success` set to `false` and an `error` object with a `code` and `message`, and SHALL NOT return an HTML error page.
4. IF the API_Client receives a response whose body cannot be parsed as JSON, THEN THE Frontend SHALL display an error message that includes the HTTP status and a fallback description within 2 seconds, and SHALL NOT display the raw JSON parse error text to the user.
5. WHEN a request targets an undefined `/api` route, THE Backend SHALL return an HTTP 404 response with a JSON content type containing `success` set to `false` and an `error` object with a `code` and `message`, and SHALL NOT return an HTML page.

### Requirement 2: Keyword Intelligence Center Loads and Harvests

**User Story:** As an advertising operator, I want the Keyword Intelligence Center to load its data and let me harvest new keywords, so that I can act on keyword insights.

The page (`KeywordIntelligencePage.tsx`) is fully wired to `fetchKeywordIntelligenceOverview`, `fetchKeywordInsights`, and `fetchKeywordSummary`, which map to `GET /api/keyword-intelligence/{overview,insights,summary}`. The page fails with the HTML/JSON parse error, indicating at least one of these endpoints returns an error. Design MUST confirm which of the three endpoints fails and whether the harvest control is wired to `POST /api/keyword-intelligence/analyze` (or a missing harvest endpoint).

#### Acceptance Criteria

1. WHEN an operator opens the Keyword Intelligence Center for the Active_Store, THE Backend SHALL respond to each of the overview, insights, and summary requests with a JSON payload (Content-Type application/json) scoped to the Active_Store and an HTTP success status within 5 seconds per request.
2. IF any of the overview, insights, or summary requests returns a non-JSON body or a non-success status, THEN THE Backend SHALL return a JSON error response containing a machine-readable error indicator, and SHALL NOT return an HTML body.
3. WHEN the overview, insights, and summary responses all load successfully, THE Frontend SHALL render the keyword health metrics, the AI summary panel, and the keyword diagnosis table without displaying an error state.
4. IF the Active_Store has no keyword records in any of the three responses (empty collection or null payload), THEN THE Frontend SHALL render an empty-state view that indicates no keyword data is available, instead of an error state.
5. WHEN an operator activates the harvest-new-keywords Primary_Action, THE Backend SHALL execute keyword harvesting for the Active_Store and return a JSON response containing the count of newly harvested keyword records (an integer of 0 or greater).
6. WHEN the harvest-new-keywords action returns a success response, THE Frontend SHALL re-fetch and re-render the insights so the displayed data reflects the harvest result, without requiring a manual page reload.
7. IF a keyword data request (overview, insights, summary, or harvest) fails or does not respond within 5 seconds, THEN THE Frontend SHALL display an error message that identifies which data failed to load and SHALL present a retry control that re-issues the failed request when activated.

### Requirement 3: Role Permission Editing

**User Story:** As an administrator, I want to edit the permissions assigned to a role, so that I can control what each role can do.

`RoleController` exposes `GET /api/roles/{id}/permissions` and `POST /api/roles/{id}/permissions`. Design MUST confirm whether the Role management page is wired to these endpoints and renders an editable permission selector.

#### Acceptance Criteria

1. WHEN an administrator opens a Role's permission editor, THE Backend SHALL return the full set of available Permissions and the subset currently assigned to that Role.
2. WHEN an administrator opens a Role's permission editor, THE Frontend SHALL render every available Permission with its assigned/unassigned state in an editable control.
3. WHEN an administrator saves a changed permission selection for a Role, THE Backend SHALL persist the assigned Permissions for that Role.
4. WHEN the Backend persists a Role's permission change, THE Frontend SHALL display a success confirmation and show the updated assignment.
5. IF an administrator lacks the `role:manage` Permission, THEN THE Backend SHALL reject the permission-assignment request with an authorization error.

### Requirement 4: Permission Management Page Displays Permissions

**User Story:** As an administrator, I want the Permission management page to list all permissions, so that I can review the system's permission catalog instead of seeing a blank page.

`PermissionController` exposes `GET /api/permissions`. The page is reported blank. Design MUST confirm whether the endpoint returns an empty list (data/seed issue) or the page is not consuming the response.

#### Acceptance Criteria

1. WHEN an administrator opens the Permission management page, THE Backend SHALL respond within 3 seconds with the catalog of defined Permissions as a JSON array, returning an empty array when no Permissions are defined.
2. WHEN the Permission catalog response is received with one or more Permissions, THE Frontend SHALL render each Permission showing both its identifier and its description text.
3. WHILE the Permission catalog request is in progress, THE Frontend SHALL display a loading indicator instead of a blank page.
4. WHERE Permissions include a category or module attribute, THE Frontend SHALL render each Permission under a heading matching its category or module value.
5. IF the Permission catalog response is received as an empty array, THEN THE Frontend SHALL display an empty-state message indicating no Permissions are defined rather than a blank page.
6. IF the Permission catalog request returns a non-success response or does not complete within 3 seconds, THEN THE Frontend SHALL display an error message indicating the catalog could not be loaded and SHALL NOT display a blank page.

### Requirement 5: User Creation

**User Story:** As an administrator, I want to add a new user, so that I can grant a colleague access to the System.

`UserController` exposes `POST /api/users` (guarded by `user:manage`) and `api.ts` exposes `createUser`. Design MUST confirm whether the failure is in the form submission, request payload shape, or a backend validation/persistence error.

#### Acceptance Criteria

1. WHEN an administrator submits the add-user form with a non-empty name between 1 and 255 characters and a syntactically valid email address (containing a single "@" with non-empty local and domain parts, up to 320 characters), THE Backend SHALL create a new User, persist the record, and return the created User record including its generated identifier.
2. WHEN the Backend returns a created User record, THE Frontend SHALL display a success confirmation message within 3 seconds and SHALL add the new User to the displayed user list without requiring a manual page reload.
3. IF the add-user form is submitted while any required field (name or email) is empty, exceeds its maximum length, or fails the email syntax check, THEN THE Frontend SHALL display a field-level validation message identifying the affected field and SHALL NOT send the create-user request to the Backend.
4. IF the submitted email matches the email of an existing User (case-insensitive comparison), THEN THE Backend SHALL reject the request, SHALL NOT create or modify any User record, and SHALL return an error response indicating that the email is already in use.
5. IF an administrator lacks the `user:manage` Permission, THEN THE Backend SHALL reject the create-user request, SHALL NOT create any User record, and SHALL return an authorization error response.
6. IF the Backend fails to persist the new User due to a storage or persistence error, THEN THE Backend SHALL return an error response indicating the creation failed and SHALL leave existing User records unchanged.

### Requirement 6: Data-Backed Functional Pages (Skeleton Pages)

**User Story:** As an operator, I want each operational page to show real data and perform its actions, so that I can do my job instead of looking at an empty UI shell.

The following pages are reported as Skeleton_Pages: 今日待办 (Today's Tasks), 审批中心 (Approval Center), 广告目标 (Ad Goals), 广告活动 (Campaigns), AI优化建议 (AI Optimization Suggestions), 产品列表 (Product List), 库存健康 (Inventory Health), 补货建议 (Replenishment), 仓库管理 (Warehouse Management), 采购订单 (Purchase Orders), 供应商管理 (Suppliers), FBA货件 (FBA Shipments), 利润看板 (Profit Dashboard), SKU利润结算 (SKU Profit), 结算管理 (Settlement Management), Review管理 (Review Management), Feedback管理 (Feedback Management), 全部任务 (All Tasks), and 报表中心 (Report Center). Corresponding `fetch*` functions already exist in `api.ts` for most of these. Design MUST classify each page as present-but-broken, frontend-stub, or missing, and enumerate the exact endpoint backing each page.

#### Acceptance Criteria

1. WHEN an operator opens any listed Skeleton_Page, THE Frontend SHALL request that page's data from its backing Backend endpoint scoped to the Active_Store where the page is store-scoped.
2. WHEN a listed Skeleton_Page's data request succeeds, THE Frontend SHALL render the returned records in the page's primary view.
3. IF a listed Skeleton_Page has no records for the Active_Store, THEN THE Frontend SHALL render an empty-state view rather than an error or a permanent loading state.
4. WHEN an operator invokes a Primary_Action on a listed Skeleton_Page, THE Backend SHALL perform the corresponding operation and return its result.
5. WHEN a Primary_Action completes, THE Frontend SHALL reflect the result in the page view without requiring a full page reload.
6. IF a listed Skeleton_Page's data request fails, THEN THE Frontend SHALL display a readable error message with a retry control.

### Requirement 7: AI Listing Studio Functions

**User Story:** As a listing specialist, I want to generate, score, and compliance-check a product listing, so that I can produce optimized listings with AI assistance.

`ListingController` exposes `POST /api/listing-ai/products/{id}/{generate,score,compliance-check}` but guards each behind a UUID check and returns empty results when the product id is not a valid UUID — consistent with the reported "产品 Listing · ASIN: N/A". Design MUST confirm how the page resolves and passes a valid product id and whether the AI generation path is fully implemented.

#### Acceptance Criteria

1. WHEN a listing specialist opens the Listing AI studio for a selected product, THE Frontend SHALL pass that product's valid identifier to the Backend listing endpoints.
2. WHEN a listing specialist requests listing generation for a valid product, THE Backend SHALL generate listing content and return it.
3. WHEN a listing specialist requests a listing score for a valid product, THE Backend SHALL return a score result.
4. WHEN a listing specialist requests a compliance check for a valid product, THE Backend SHALL return a compliance result identifying any issues.
5. WHEN listing content, a score, or a compliance result is returned, THE Frontend SHALL render the result in the studio.
6. IF the studio is opened without a resolvable product, THEN THE Frontend SHALL prompt the specialist to select a product rather than displaying "ASIN: N/A" with non-functional actions.

### Requirement 8: Product Upload Navigation

**User Story:** As a listing specialist, I want the product upload entry point to navigate to the upload workflow, so that I can start an upload from the expected place.

The reported behavior is that product upload "需要跳转" (should route/navigate properly). Design MUST confirm the current behavior of the product upload control and the intended destination route.

#### Acceptance Criteria

1. WHEN a listing specialist activates the product upload entry point, THE Frontend SHALL navigate to the product upload workflow route.
2. WHERE the product upload workflow requires a selected product or Store, THE Frontend SHALL carry that context into the upload workflow on navigation.
3. WHEN the product upload workflow route loads, THE Frontend SHALL render the upload workflow rather than a blank or unrelated view.

### Requirement 9: Automation Rule Creation

**User Story:** As an operations manager, I want to add a new automation rule, so that I can automate repetitive optimization actions.

`AutomationController` exposes `POST /api/automation/policies` (guarded by `automation:manage`). The page is reported unable to add a rule. Design MUST confirm whether the add-rule control is wired to this endpoint and what payload the policy requires.

#### Acceptance Criteria

1. WHEN an operations manager submits the add-automation-rule form with the required fields, THE Backend SHALL create a new automation policy and return the created record.
2. WHEN the Backend creates an automation policy, THE Frontend SHALL display a success confirmation and show the new rule in the rules list.
3. IF the add-automation-rule form is submitted with a missing or invalid required field, THEN THE Frontend SHALL display a validation message and SHALL NOT submit the request.
4. IF an operations manager lacks the `automation:manage` Permission, THEN THE Backend SHALL reject the create request with an authorization error.

### Requirement 10: Feishu Group Binding and Notification Rules

**User Story:** As an administrator, I want to bind Feishu group chats and configure notification rules, so that the System can send operational notifications to the right chats.

`FeishuIntegrationPage.tsx` currently renders an `ErpFeatureUnderConstruction` placeholder and a placeholder `handleSave` (a `setTimeout`), confirming these are frontend-stubs. `FeishuController` exposes connect/list/update/test-message endpoints. Design MUST confirm which Backend endpoints exist for group-chat binding and notification-rule configuration and which must be created.

#### Acceptance Criteria

1. WHEN an administrator opens the Feishu group-chat binding view, THE Frontend SHALL render a working binding interface rather than an "under construction" placeholder.
2. WHEN an administrator binds a Feishu group chat, THE Backend SHALL persist the binding and return the saved binding.
3. WHEN an administrator opens the Feishu notification-rule view, THE Frontend SHALL render a working rule-configuration interface rather than an "under construction" placeholder.
4. WHEN an administrator saves a notification rule, THE Backend SHALL persist the rule and return the saved rule.
5. WHEN an administrator saves a Feishu configuration, THE Frontend SHALL call the Backend and display the actual save result rather than a simulated confirmation.
6. IF an administrator lacks the `feishu:manage` Permission, THEN THE Backend SHALL reject Feishu configuration changes with an authorization error.

### Requirement 11: Human-Readable Audit Log

**User Story:** As a compliance reviewer, I want audit log entries shown with readable labels and meaningful data, so that I can understand what happened without decoding raw codes.

The Audit_Log currently shows raw codes such as `AUTHZ_PERMIT` for action and `AUTHORIZATION` for entity type, with empty 实体ID (entity id) and 来源 (source). Design MUST confirm whether readable labels are produced on the Backend (`AuditLogVo`) or mapped in the Frontend, and which actions/entity types must be covered.

#### Acceptance Criteria

1. WHEN the audit log is displayed, THE Frontend SHALL render each entry's action using a human-readable label rather than a raw action code.
2. WHEN the audit log is displayed, THE Frontend SHALL render each entry's entity type using a human-readable label rather than a raw entity-type code.
3. WHERE an Audit_Log entry has an associated entity id and source, THE Frontend SHALL display the entity id and source values.
4. THE Frontend SHALL provide audit-log filters for the action values 全部操作 (all), 登录 (login), 创建 (create), 更新 (update), 删除 (delete), 生成 (generate), 应用 (apply), 回滚 (rollback), 审批 (approve), and 忽略 (ignore).
5. THE Frontend SHALL provide audit-log filters for the entity types 用户 (user), 店铺 (store), 商品 (product), 广告活动 (campaign), 关键词 (keyword), 报告 (report), 建议 (recommendation), 任务 (task), and 审批 (approval).
6. WHEN a reviewer selects an action or entity-type filter, THE Backend SHALL return only the Audit_Log entries matching the selected filter.

### Requirement 12: Data Quality Page Reliability

**User Story:** As a data steward, I want the Data Quality page to load without errors, so that I can review and resolve data quality issues.

The page reports "An unexpected error occurred". `DataQualityController` exposes `GET /api/data-quality/issues`, `POST /api/data-quality/check`, and resolve/ignore endpoints (the resolve/ignore handlers contain TODOs for extracting the user id). Design MUST confirm whether the list endpoint errors and whether resolve/ignore complete without a valid user context.

#### Acceptance Criteria

1. WHEN a data steward opens the Data Quality page for the Active_Store, THE Backend SHALL return the list of data quality issues as JSON.
2. WHEN the data quality issues load, THE Frontend SHALL render them without an unexpected-error state.
3. WHEN a data steward runs a data quality check for the Active_Store, THE Backend SHALL execute the check and return its result.
4. WHEN a data steward resolves or ignores a data quality issue, THE Backend SHALL update the issue's status and record the acting user.
5. IF the Active_Store has no data quality issues, THEN THE Frontend SHALL render an empty-state view rather than an error.

### Requirement 13: Dynamic Multiple Platform Connections

**User Story:** As an administrator, I want to add multiple platform connections and choose the platform for each, so that I am not limited to a fixed set of five platforms.

`ApiConnectionsPage` currently presents a fixed list of five platforms, while `ApiSyncController` already supports `POST /api/platform-connections` with a `PlatformConnectionDto`, per-platform credential field schemas (`/{platform}/fields`), and saved config (`/{platform}/config`). This is primarily a frontend-stub limitation. Design MUST confirm the supported set of platform keys and the connection creation payload.

#### Acceptance Criteria

1. WHEN an administrator opens the API connections page, THE Frontend SHALL render the list of existing Platform_Connections returned by the Backend rather than a hardcoded list.
2. WHEN an administrator chooses to add a connection, THE Frontend SHALL allow the administrator to select the target Platform from the set of supported platforms.
3. WHEN an administrator selects a Platform for a new connection, THE Frontend SHALL render the credential fields required for that Platform.
4. WHEN an administrator submits a new connection with valid credentials, THE Backend SHALL create a Platform_Connection for the selected Platform and return the created connection.
5. WHERE multiple connections target the same Platform, THE System SHALL allow each connection to be created and listed independently.
6. WHEN an administrator tests a saved connection, THE Backend SHALL attempt the connection and return a success or failure result.
7. WHEN an administrator deletes or disconnects a connection, THE Backend SHALL remove or deactivate that connection and THE Frontend SHALL reflect the change in the list.

### Requirement 14: Store and Platform Connection Relationship

**User Story:** As an administrator, I want the Store management UI to clearly model the relationship between a Store and its platform connections, so that I understand that one store can have many credential channels.

A Store is a selling entity; a Platform_Connection is a credential channel for that Store; one Store has many Platform_Connections (the `seller_account_id` column already exists). Design MUST confirm the data model linking Platform_Connection to Store and the UI placement of connection management within store management.

#### Acceptance Criteria

1. WHEN an administrator views a Store, THE Frontend SHALL display the Platform_Connections associated with that Store.
2. WHEN an administrator adds a Platform_Connection from a Store's context, THE Backend SHALL associate the created connection with that Store.
3. THE Frontend SHALL present a Store and a Platform_Connection as distinct concepts, with the Store as the selling entity and each connection as a credential channel to a Platform.
4. WHEN an administrator views a Store with multiple Platform_Connections, THE Frontend SHALL list each connection with its Platform and connection status.
5. WHERE a Platform_Connection is not associated with any Store, THE Frontend SHALL indicate that the connection is unassigned.

### Requirement 15: Login Log Display

**User Story:** As a security administrator, I want the login log to display records, so that I can review authentication activity.

`LoginLogController` exposes `GET /api/login-logs`. Login logs are reported as no longer displaying. Design MUST confirm whether the endpoint returns data, whether login attempts are being recorded, and whether the page consumes the response shape.

#### Acceptance Criteria

1. WHEN a security administrator opens the login log page, THE Backend SHALL return login records as JSON.
2. WHEN login records load, THE Frontend SHALL render each record with its user, status, source, and timestamp.
3. WHEN a user authentication attempt occurs, THE Backend SHALL record a Login_Log entry for that attempt.
4. WHEN a security administrator filters login records by status, THE Backend SHALL return only the records matching the selected status.
5. IF no login records exist, THEN THE Frontend SHALL render an empty-state view rather than a blank or error state.

### Requirement 16: Correct Navigation Menu Categorization

**User Story:** As an operator, I want navigation menu items grouped under the correct categories, so that I can find features where I expect them.

Navigation is defined by `navSections` in `Layout.tsx`. Several items are reported as placed under the wrong category. The corrected grouping reference for the AI advertising surfaces is the SparkX_Reference menu taxonomy below; Design MUST reconcile this taxonomy with the project's existing routes and define the final item-to-category mapping for the affected items.

SparkX_Reference menu taxonomy (sidebar group → items):
- 首页 (Home dashboard)
- 自定义看板 (Custom dashboard)
- AI广告优化 (AI Ad Optimization): 全部搜索广告 (All Search Ads), 全部广告组合 (All Ad Portfolios), AI托管 (AI Hosting), 智能诊断 (Smart Diagnosis), AI通知 (AI Notifications)
- Insight Agent (conversational AI analyst)
- 高阶广告设置 (Advanced Ad Settings): 自动化规则 (Automation Rules), 广告位锁定 (Ad Placement Lock)
- 效率工具 (Efficiency Tools): 标签管理 (Tag Management), 关键词 (Keywords — 词库 keyword library + 关键词推荐 recommendations), 排名监控 (Rank Monitoring), 创意素材 (Creative Assets)
- 数据洞察 (Data Insights): 全部商品列表 (All Product List), 商品列表 (Product List), 自定义报告 (Custom Reports), 品牌指标 (Brand Metrics), 市场洞察 (Market Insights), SQP分析 (SQP Analysis)
- AMC数据工作室 (AMC Data Studio): AMC模型库 (AMC Model Library), 用户受众创建 (User Audience Creation)
- 帮助中心 (Help Center)

#### Acceptance Criteria

1. THE Frontend SHALL group each navigation item under the Nav_Section that matches the item's functional domain, per the corrected mapping defined in design.
2. WHEN an operator views the sidebar, THE Frontend SHALL render each navigation item under exactly one Nav_Section.
3. WHERE an operator lacks the Permission required by a navigation item, THE Frontend SHALL omit that item, and SHALL omit any Nav_Section left with no visible items.
4. WHEN a navigation item is activated, THE Frontend SHALL navigate to that item's route.
5. WHERE a navigation item corresponds to a surface in the SparkX_Reference menu taxonomy, THE Frontend SHALL place that item under the Nav_Section that matches the SparkX_Reference group for that surface, as reconciled with the project's routes in design.

### Requirement 17: Complete Chinese Localization

**User Story:** As a Chinese-speaking user, I want all UI text in Chinese, so that I can use the System in my language without encountering untranslated strings.

A Localization_Catalog already exists under `frontend/src/app/i18n/`, but a small amount of UI text remains untranslated. Design MUST identify the untranslated strings and the convention for adding them to the catalog.

#### Acceptance Criteria

1. THE Frontend SHALL display all user-facing static text in Chinese.
2. WHERE a user-facing string is rendered, THE Frontend SHALL source its text from the Localization_Catalog.
3. WHEN a new user-facing string is added during this work, THE Frontend SHALL add its Chinese text to the Localization_Catalog rather than hardcoding a non-Chinese literal.
4. IF a Localization_Catalog lookup finds no entry for a key, THEN THE Frontend SHALL surface the missing key during development so it can be added.

---

## AI Advertising Module Standard (Requirements 18+)

The requirements in this section establish the **authoritative standard** for the AI advertising module for this round of changes, modeled on the SparkX_Reference (`ai.sparkx.io`). This is a large scope derived from a reference platform: the design phase MUST map each surface below onto the project's existing modules (advertising, automation, keyword, recommendation, dashboard, report), classify each as present, present-but-broken, or missing, and scope the implementation to the depth that fits this project. Each requirement notes its likely module mapping. Where a surface is reported as a Skeleton_Page in Requirement 6, the standard defined here supersedes the generic skeleton requirement for that surface.

### Requirement 18: AI Advertising Dashboard (Sales Overview, AI Actions, AI Usage)

**User Story:** As an advertising operator, I want a home dashboard that summarizes sales, the AI actions taken, and AI coverage, so that I can see at a glance how the AI advertising module is performing for my store.

Likely module mapping: dashboard module (home page), with data drawn from the advertising and automation modules. Design MUST classify the home dashboard as present, present-but-broken, or missing.

#### Acceptance Criteria

1. WHEN an operator opens the AI advertising home dashboard for the Active_Store, THE Frontend SHALL render a Sales Overview (销售总览) showing the metrics 总销售额 (total sales), 广告花费 (ad spend), 广告销售额 (ad sales), 广告订单数 (ad orders), TACoS, and ACoS, each as a value with its period-over-period delta, scoped to the Active_Store, marketplace, currency, and selected date range.
2. WHEN the Sales Overview is rendered, THE Frontend SHALL render a sales trend chart with a day/week/month granularity toggle and two series, ad spend and ad sales, on a dual axis.
3. WHEN the home dashboard loads, THE Frontend SHALL render an AI Actions panel (AI动作) that lists, for SP, each AI_Action with its action count and its measured impact, covering keyword harvesting (关键词收割) with revenue protected and harvested-keyword count, negative keywords (关键词否定) with spend saved and negative count, bid optimization (竞价优化) with affected-campaign count, budget optimization (预算优化), ad-structure optimization (广告结构优化), dayparting budget (分时段预算), dayparting bid (分时竞价), and quick-search test (快搜测试) with first-click duration.
4. WHEN the home dashboard loads, THE Frontend SHALL render an AI Usage panel (AI使用) showing AI coverage percentage, AI ad spend, and AI ad sales for the Active_Store.
5. WHEN the home dashboard loads, THE Frontend SHALL render an AI Notifications summary panel showing each of the four AI_Notification categories with its pending count.
6. WHEN an operator changes the date range, marketplace, or currency in the top bar, THE Frontend SHALL re-request and re-render the Sales Overview, trend chart, AI Actions, and AI Usage panels scoped to the new selection.
7. IF the Active_Store has no advertising data for the selected date range, THEN THE Frontend SHALL render an empty-state view for each panel rather than an error state.
8. IF any dashboard data request fails or does not respond within 5 seconds, THEN THE Frontend SHALL display a readable error message with a retry control for the affected panel.

### Requirement 19: All Search Ads Campaign Management Surface

**User Story:** As an advertising operator, I want a single search-ads workspace with tabs, filters, a data-trend panel, and a campaign table, so that I can manage all of my SP/SB/SD campaigns in one place.

Likely module mapping: advertising module (campaigns, ad groups, targeting, search terms). Design MUST enumerate which tabs and table columns are present, present-but-broken, or missing.

#### Acceptance Criteria

1. WHEN an operator opens the All Search Ads workspace for the Active_Store, THE Frontend SHALL render tabbed navigation with tabs for 广告活动 (Campaigns), AI托管 (AI Hosting), 广告组 (Ad Groups), 推广商品 (Promoted Products), 投放 (Targeting), 否定投放 (Negative Targeting), 搜索词 (Search Terms), 购买的其他商品 (Other Purchased Products), 竞价调整 (Bid Adjustments), SP预算上限 (SP Budget Caps), and 操作日志 (Operation Log).
2. WHEN the Campaigns tab is active, THE Frontend SHALL render a data-trend panel showing 花费 (spend), 销售额 (sales), 订单数 (orders), ACoS, 点击成本 (CPC), and 订单成本 (cost per order), each with its period-over-period delta, accompanied by a trend chart.
3. WHEN the Campaigns tab is active, THE Frontend SHALL render a campaign table where each row shows an enable/pause toggle, the campaign name (广告活动), targeting status (投放状态: 投放中 / 已暂停 / 已投放 / 审核中), store (店铺), hosting goal (托管目标), Target_ACoS (目标ACOS), recent ACoS (ACOS最近), an AI-managed indicator (AI入格), targeting type (投放类型: 自动 / 手动), and row actions.
4. WHEN an operator applies a filter from the set 智能筛选 (smart filter), ad type (SP / SB / SD), 父ASIN (parent ASIN), 广告组合 (ad portfolio), 投放目标 (targeting goal), 广告活动状态 (campaign status), or a Target_ACoS minimum/maximum range, THE Backend SHALL return only the Campaigns matching the active filters scoped to the Active_Store.
5. WHEN an operator toggles a campaign's enable/pause control, THE Backend SHALL update that Campaign's state and THE Frontend SHALL reflect the new state without a full page reload.
6. WHEN an operator activates the create-campaign Primary_Action (新建广告活动), THE Frontend SHALL open the campaign-creation workflow, and on submission THE Backend SHALL create the Campaign and return the created record.
7. WHEN an operator selects multiple rows and invokes a bulk operation (批量处理), THE Backend SHALL apply the operation to each selected Campaign and return a per-item result.
8. IF the Active_Store has no Campaigns matching the active filters, THEN THE Frontend SHALL render an empty-state view rather than an error state.

### Requirement 20: Ad Portfolio Management

**User Story:** As an advertising operator, I want to view and create ad portfolios with their budgets and performance, so that I can organize campaigns into portfolios.

Likely module mapping: advertising module. Design MUST classify ad portfolio support as present, present-but-broken, or missing.

#### Acceptance Criteria

1. WHEN an operator opens the All Ad Portfolios page for the Active_Store, THE Frontend SHALL render a portfolio list where each row shows targeting status (投放状态), store (店铺), start and end dates, budget type (预算类型), budget (预算), campaign count (广告活动数量), impressions (曝光量), clicks (点击量), click-through rate (点击率), spend (花费), CPC (点击成本), orders (订单数), and sales (销售额).
2. WHEN an operator activates the create-portfolio Primary_Action (新建广告组合), THE Frontend SHALL open the portfolio-creation workflow, and on submission THE Backend SHALL create the Ad_Portfolio and return the created record.
3. WHEN an Ad_Portfolio has no budget cap, THE Frontend SHALL display its budget type as 无预算上限 (no budget cap).
4. IF the Active_Store has no Ad_Portfolios, THEN THE Frontend SHALL render an empty-state view rather than an error state.

### Requirement 21: AI Hosting with Target ACoS Auto-Optimization

**User Story:** As an advertising operator, I want to place a campaign under AI hosting by setting a hosting goal and target ACoS, so that the AI continuously optimizes the campaign toward my efficiency target.

Likely module mapping: advertising module (campaign state) + automation module (optimization execution). This behavior is part of the authoritative AI advertising standard. Design MUST classify AI_Hosting as present, present-but-broken, or missing and define the optimization execution path.

#### Acceptance Criteria

1. WHEN an operator places a Campaign under AI_Hosting by assigning a Hosting_Goal and a Target_ACoS, THE Backend SHALL persist the Campaign's hosting state, Hosting_Goal, and Target_ACoS and return the updated Campaign.
2. WHILE a Campaign is under AI_Hosting, THE AI_Advertising_Module SHALL automatically adjust the Campaign's bids, budget, keywords, and negative keywords toward the assigned Target_ACoS.
3. WHEN a Campaign is under AI_Hosting, THE Frontend SHALL display the AI-managed indicator (AI入格), the Hosting_Goal, and the Target_ACoS on the Campaign's row.
4. WHEN an operator changes a hosted Campaign's Target_ACoS, THE Backend SHALL persist the new Target_ACoS and THE AI_Advertising_Module SHALL optimize toward the updated target on subsequent optimization runs.
5. WHEN an operator removes a Campaign from AI_Hosting, THE Backend SHALL persist the un-hosted state and THE AI_Advertising_Module SHALL stop applying automatic optimizations to that Campaign.
6. IF an operator attempts to place a Campaign under AI_Hosting without a Target_ACoS, THEN THE Frontend SHALL display a validation message and SHALL NOT submit the hosting request.

### Requirement 22: Smart Diagnosis

**User Story:** As an advertising operator, I want to run diagnosis tasks on my products' ad structure, so that I can identify and act on structural issues.

Likely module mapping: recommendation module + advertising module. Design MUST classify Smart_Diagnosis as present, present-but-broken, or missing.

#### Acceptance Criteria

1. WHEN an operator activates the create-diagnosis-task Primary_Action (新建诊断任务) for a parent ASIN, THE Backend SHALL create a Smart_Diagnosis task scoped to the Active_Store and return the created task.
2. WHEN a Smart_Diagnosis task completes, THE Backend SHALL produce a diagnosis result analyzing the product's ad structure, and THE Frontend SHALL render the diagnosis result.
3. WHEN the Smart Diagnosis page loads, THE Frontend SHALL render each diagnosis task with its product (parent ASIN), update frequency, creator, and last-diagnosis time.
4. IF the Active_Store has no diagnosis tasks, THEN THE Frontend SHALL render an empty-state view rather than an error state.

### Requirement 23: AI Notifications Work-Item Workflow

**User Story:** As an advertising operator, I want AI-raised notifications organized into work-item categories that I can act on, so that I can apply optimizations and confirm AI target changes.

Likely module mapping: recommendation module (suggestions) + automation module (applied actions). Design MUST classify each notification category as present, present-but-broken, or missing.

#### Acceptance Criteria

1. WHEN an operator opens the AI Notifications page for the Active_Store, THE Frontend SHALL render the four AI_Notification categories — 广告运营核心关注 (core ops attention), 广告活动一键优化 (one-click campaign optimization), 发现高潜广告活动 (discover high-potential campaigns), and AI目标修正待确认 (AI target correction pending confirmation) — each with its pending count and completed count.
2. WHEN an operator opens an AI_Notification category, THE Frontend SHALL render its items in a pending list (待处理) and a closed list (已结束).
3. WHEN an operator applies a one-click optimization on a pending AI_Notification item, THE Backend SHALL perform the optimization, move the item to the closed state, and return the result.
4. WHEN an operator confirms or rejects an AI target correction item, THE Backend SHALL apply or discard the proposed Target_ACoS change accordingly, move the item to the closed state, and return the result.
5. WHEN an operator opens the AI notification configuration (前往AI通知配置), THE Frontend SHALL render the configuration interface that controls which core-ops items are raised.
6. IF a category has no pending items, THEN THE Frontend SHALL render an empty-state view for that category's pending list rather than an error state.

### Requirement 24: Insight Agent Conversational Analysis

**User Story:** As an advertising operator, I want a conversational AI analyst, so that I can ask data questions in natural language and receive insights and recommended actions.

Likely module mapping: AI module + recommendation/report modules for data sources. Design MUST classify the Insight_Agent as present, present-but-broken, or missing.

#### Acceptance Criteria

1. WHEN an operator submits a data query or analysis request to the Insight_Agent, THE Backend SHALL process the request scoped to the Active_Store and return insights and recommended next actions.
2. WHEN the Insight_Agent page loads, THE Frontend SHALL render suggested prompts including analyzing product lines, comparing last-30-day performance, a weekly store summary, diagnosing a high-ACoS campaign, and reviewing hosting groups.
3. WHERE the Insight_Agent supports selectable analysis sources and a Premium mode, THE Frontend SHALL allow the operator to select an analysis source and toggle Premium mode before submitting a request.
4. IF an Insight_Agent request fails or does not complete within its response window, THEN THE Frontend SHALL display a readable error message and SHALL allow the operator to resubmit the request.

### Requirement 25: Automation Rule Templates (Condition to Action)

**User Story:** As an operations manager, I want reusable automation rule templates that apply a condition-to-action to linked objects, so that I can automate optimization across many campaigns and targets.

Likely module mapping: automation module (extends Requirement 9). Design MUST classify rule templates and linked-object application as present, present-but-broken, or missing.

#### Acceptance Criteria

1. WHEN an operations manager opens the Automation Rules page for the Active_Store, THE Frontend SHALL render each rule template with its template name (模板名称), template type (模板类型), store (店铺), linked-object count (关联对象数量), and status.
2. WHEN an operations manager activates the create-template Primary_Action (新建模板) and submits the required fields, THE Backend SHALL create the rule template and return the created record.
3. WHEN a rule template is linked to one or more Campaigns or targeting objects, THE AI_Advertising_Module SHALL evaluate the template's condition against each linked object and apply the template's action (for example, a bid adjustment or a negative keyword) when the condition is met.
4. WHEN an operations manager selects multiple rule templates and invokes a bulk operation (批量操作), THE Backend SHALL apply the operation to each selected template and return a per-item result.
5. IF the create-template form is submitted with a missing or invalid required field, THEN THE Frontend SHALL display a validation message and SHALL NOT submit the request.
6. IF an operations manager lacks the `automation:manage` Permission, THEN THE Backend SHALL reject the create-template request with an authorization error.

### Requirement 26: Ad Placement Lock Strategies

**User Story:** As an advertising operator, I want to define placement-lock strategies that hold my SP ads in a chosen placement, so that I can secure high-value ad positions.

Likely module mapping: advertising module (bid adjustments) + automation module. Design MUST classify Ad_Placement_Lock as present, present-but-broken, or missing.

#### Acceptance Criteria

1. WHEN an operator opens the Ad Placement Lock page, THE Frontend SHALL render tabs for 策略管理 (strategy management), 任务管理 (task management), and AMS实时数据 (AMS real-time data).
2. WHEN an operator activates the add-strategy Primary_Action (添加策略), THE Frontend SHALL allow the operator to select a target placement (for example, 首页1-1位 top-of-page or 第1页5-8位) for an SP Campaign and specify a keyword bid range, and on submission THE Backend SHALL create the Ad_Placement_Lock strategy and return the created record.
3. WHILE an Ad_Placement_Lock strategy is active, THE AI_Advertising_Module SHALL adjust the linked keyword bids within the configured bid range to hold the ad in the targeted placement.
4. IF an operator submits a placement-lock strategy with an invalid bid range (where the minimum exceeds the maximum), THEN THE Frontend SHALL display a validation message and SHALL NOT submit the request.

### Requirement 27: Keyword Library and Recommendations

**User Story:** As an advertising operator, I want keyword libraries and keyword recommendations, so that I can organize keywords and feed harvesting and negation actions.

Likely module mapping: keyword module + recommendation module (extends Requirement 2). Design MUST classify the Keyword_Library and recommendations as present, present-but-broken, or missing.

#### Acceptance Criteria

1. WHEN an operator opens the Keywords page, THE Frontend SHALL render a 词库 (keyword library) tab and a 关键词推荐 (keyword recommendations) tab.
2. WHEN the keyword library tab loads, THE Frontend SHALL render each Keyword_Library with its associated products (关联商品), keyword count (关键词数量), library type (词库类型), and last and next execution times.
3. WHEN an operator activates the create-library Primary_Action (创建词库) and submits the required fields, THE Backend SHALL create the Keyword_Library and return the created record.
4. WHEN keyword recommendations are displayed, THE Frontend SHALL allow the operator to act on a recommendation to add it as a harvested keyword or as a negative keyword, and THE Backend SHALL apply the chosen action scoped to the Active_Store.
5. IF the Active_Store has no Keyword_Libraries, THEN THE Frontend SHALL render an empty-state view rather than an error state.

### Requirement 28: Rank Monitoring

**User Story:** As an advertising operator, I want to monitor my products' keyword rankings, so that I can track organic and ad rank over time within my quota.

Likely module mapping: keyword module + report module. Design MUST classify Rank Monitoring as present, present-but-broken, or missing.

#### Acceptance Criteria

1. WHEN an operator activates the add-monitor-task Primary_Action (添加监控任务) for a product's keywords, THE Backend SHALL create a rank-monitoring task scoped to the Active_Store and return the created task.
2. WHEN the Rank Monitoring page loads, THE Frontend SHALL render, for each monitored keyword, its organic rank (自然排名) and its ad rank (广告排名).
3. WHEN the Rank Monitoring page loads, THE Frontend SHALL display the consumed and total monitoring quota (for example, 234 of 400 keywords).
4. IF adding a monitoring task would exceed the monitoring quota, THEN THE Frontend SHALL display a message indicating the quota is exhausted and SHALL NOT submit the request.

### Requirement 29: Creative Asset Library

**User Story:** As a listing or advertising specialist, I want a searchable creative asset library, so that I can upload and reuse images and videos in my ads.

Likely module mapping: advertising module (creatives) + a media storage concern. Design MUST classify the creative asset library as present, present-but-broken, or missing.

#### Acceptance Criteria

1. WHEN a specialist opens the Creative Assets page, THE Frontend SHALL render the asset library with each asset's type (for example, 生活方式图, 场景图, 高清图组, 营销宣传图).
2. WHEN a specialist uploads a creative asset, THE Backend SHALL store the asset and return the stored asset record.
3. WHEN a specialist searches the asset library by name, tag, ASIN, or creator, THE Backend SHALL return only the assets matching the search criteria.
4. IF no creative assets match the search criteria, THEN THE Frontend SHALL render an empty-state view rather than an error state.

### Requirement 30: Data Insights, SQP, and AMC Surfaces

**User Story:** As an analyst, I want product-performance, brand-metric, market-insight, search-query-performance, and AMC surfaces, so that I can analyze store and market performance in depth.

Likely module mapping: report module + recommendation module; AMC features may be gated behind account activation. Design MUST classify each surface as present, present-but-broken, or missing and define the depth implemented for this project.

#### Acceptance Criteria

1. WHEN an analyst opens the product list surface (全部商品列表 / 商品列表) for the Active_Store, THE Frontend SHALL render, per parent ASIN and ASIN, the metrics 广告销额 (ad sales), 广告花费 (ad spend), TACoS, 总销额 (total sales), 总订单数 (total orders), and inventory status.
2. WHEN an analyst opens the custom reports surface (自定义报告), THE Frontend SHALL render the analyst's scheduled custom reports and display the consumed and total report quota (for example, 9 of 20).
3. WHEN an analyst opens the brand metrics surface (品牌指标), THE Frontend SHALL render category-brand metrics including 品牌顾客总数 (total brand customers), 顾客互动率 (engagement rate), 顾客转化率 (conversion rate), and 品牌新客销售额占比 (new-to-brand sales share).
4. WHEN an analyst opens the market insights surface (市场洞察), THE Frontend SHALL render the available market-monitoring reports.
5. WHEN an analyst opens the SQP analysis surface (SQP分析), THE Frontend SHALL render brand-versus-market funnel metrics including click-through rate, add-to-cart rate, conversion rate, impressions (曝光量), and impression share (曝光份额).
6. WHEN an analyst opens the AMC data studio (AMC数据工作室), THE Frontend SHALL render the AMC model library (AMC模型库) templates and the user audience creation (用户受众创建) templates.
7. WHERE an AMC feature is gated behind account activation, THE Frontend SHALL indicate that the feature requires activation rather than presenting it as available.
8. IF a Data Insights surface has no data for the Active_Store, THEN THE Frontend SHALL render an empty-state view rather than an error state.
