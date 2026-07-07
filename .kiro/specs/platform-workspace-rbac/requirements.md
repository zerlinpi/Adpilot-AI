# Requirements Document

## Introduction

This feature delivers a **platform-wide information-architecture, connection-flow, RBAC, and Google Ads** rework for the **AdPilot AI** cross-border e-commerce operations platform, spanning the React/TypeScript frontend (`frontend/`, navigation in `frontend/src/app/components/Layout.tsx`) and the Java 17 / Spring Boot 3.2.5 backend (`backend-java/`, MyBatis-Plus + JPA + MySQL 8.0 + Redis). The database layer follows the project convention that `backend-java/db/schema.sql` is the single source of truth (no Flyway, no runtime DDL), so every new persisted field or table this spec introduces is added there, consistent with the `project-fix-and-cleanup` schema strategy.

The rework targets six coordinated areas verified against the live codebase:

1. **四大块导航重构** — collapse the current twelve flat navigation groups in `Layout.tsx` (`navSections`) into four top-level blocks (亚马逊 / 独立站 / 物流 / 财务统计), each a click-to-expand/collapse parent that reveals a second-level submenu. System-level utilities (系统设置, 飞书机器人, 审计回滚, CSV导入) move out of the four blocks into an account/top area.
2. **连接流程合并** — remove the current two-step "create API connection, then connect store" flow (`ApiConnectionsPage` + `data-sync`) in favor of a single store-connection entry per block: one Amazon store-connection wizard (one-step OAuth authorize + bind store, reusing the existing `AmazonAdsConnectWizard`) and one independent-site store-connection entry (Shopify / WooCommerce / TikTok credential entry in one place; Google Ads bound to the independent-site store).
3. **Google Ads 完整能力（含 AI 托管）** — surface a Google Ads workspace that pulls campaigns and performance reports through the already-built `GoogleAdsConnector`, lets operators manually create campaigns and adjust bid/budget/status through the already-built `GoogleAdsWriteConnector` via the platform Operation → Outbox write-back pipeline, and adds a Google-Ads-specific AI hosting engine that generates create/adjust decisions routed through the same approval / execution / attribution pipeline established by the `amazon-ads-ai-hosting-system` spec.
4. **客服工单归入亚马逊 + AI 处理** — relocate customer tickets (买家消息 → 工单) under the 亚马逊 block and add AI assistance that drafts replies, classifies tickets, and suggests handling actions, with human review/confirmation.
5. **店铺分组 + 三维 RBAC** — introduce a first-class **Store Group** concept (every store belongs to one store group; Amazon operations groups are dynamically addable, independent-site is its own group system) and a three-dimensional permission model assignable per account: platform access, store-group data scope, and functional permission. Data isolation is treated as a security boundary that MUST be proven by correctness-property tests.
6. **全按钮功能巡检** — an acceptance dimension requiring a page-by-page audit of every button/action's front-to-back wiring (responds to click, calls the correct endpoint, surfaces errors), enumerating and repairing broken items.

### Existing capabilities this spec reuses (NOT redefined)

- **RBAC primitives** — `users`, `roles`, `permissions` (code format `module:action`), `role_permissions`, `user_roles`, `data_scopes` (`scope_type` ∈ `all_company`/`department`/`own`/`assigned_store`/`assigned_product`, with `store_ids`/`product_ids` JSON), `user_departments`, `user_stores`, plus the `Permission_Service` / `Data_Scope_Service` / `EffectiveScope` model and super-administrator bypass delivered by `core-platform-completion`. This spec **extends** that model with store-group and platform dimensions while preserving backward compatibility (see Requirement 18).
- **Connection layer** — the `apisync` module `PlatformConnector` SPI and per-platform connectors, including the existing `GoogleAdsConnector` (read/GAQL) and `GoogleAdsWriteConnector` (write), and the `AmazonAdsConnectWizard` one-step authorization flow.
- **AI hosting pipeline** — the `amazon-ads-ai-hosting-system` spec already implements the Amazon side (Operation state machine, Outbox write-back, decision storage, approval routing, effect attribution, Feishu notification). Google Ads hosting maximizes reuse of that platform-generic pipeline rather than rebuilding it.
- **Store grouping (label)** — `stores.store_group` exists today only as a free-text label used by the store switcher (`core-platform-completion` Req 5.2.4) and the `platformScope` nav filter (`amazon`/`independent_site`/`tiktok`). This spec **promotes** store grouping to a first-class entity used for data isolation; the legacy label is reconciled in Requirement 18.

### Scope boundaries and overlap with existing specs

- **`core-platform-completion`** owns RBAC enforcement primitives, data-scope dimensions, store switcher, and the platform write-back contract. This spec **extends** the scope model (store-group + platform dimensions) and **consumes** the write-back contract; it does not redefine sync or the base scope dimensions.
- **`advertising-workspace-rework`** owns the Amazon advertising Operation/Outbox/Sync_State model, the four-group advertising tab regrouping, and the `local_configuration` vs `platform_mutation` distinction. Google Ads write-back and hosting in this spec **reuse** that Operation pipeline.
- **`amazon-ads-ai-hosting-system`** owns the Amazon hosting engines (V1/V2/V3), decision snapshots, risk/approval routing, effect attribution, and notification integration. The Google Ads hosting engine in this spec is a **net-new platform-specific engine** that reuses the platform-generic Optimization_Coordinator, decision storage, approval, Outbox, and attribution surfaces.
- **`platform-ux-logistics-enhancements`** owns reusable table components and logistics depth. This spec **regroups the navigation** that surfaces those pages without redefining them.

Acceptance criteria are written in EARS format and are technology-neutral about implementation while remaining aware of the existing stack. Display copy is given in Chinese where it is user-facing, matching the existing UI.

## Glossary

- **System**: The AdPilot AI application as a whole (frontend + backend) unless a more specific component is named.
- **Frontend**: The React + TypeScript single-page application in `frontend/` that consumes the Backend under the `/api` prefix.
- **Backend**: The Spring Boot service in `backend-java/` exposing REST endpoints under `/api`.
- **Navigation_Shell**: The Frontend navigation component (`Layout.tsx`) that renders the left menu, the avatar dropdown, and the top area.
- **Nav_Block**: One of the four top-level navigation blocks — 亚马逊, 独立站, 物流, 财务统计 — each a click-to-expand/collapse parent containing a second-level submenu of Nav_Items.
- **Nav_Item**: A single second-level menu entry mapping to a route, a required functional permission, and a Platform_Access dimension.
- **Account_Area**: The avatar-dropdown / top region that hosts system-level utilities (系统设置, 飞书机器人, 审计回滚, CSV导入) that do not belong to any Nav_Block.
- **Store**: An internal record representing a single selling channel instance on a single marketplace (an Amazon storefront, a Shopify store, a WooCommerce site, or a TikTok Shop).
- **Store_Group**: A first-class grouping entity that every Store belongs to exactly one of. A Store_Group has a platform family (`amazon` or `independent_site`) and is the unit of the Store-Group Data Scope. Amazon operations Store_Groups (for example 亚马逊一组, 亚马逊二组) are dynamically addable; independent-site Stores belong to the independent-site Store_Group system.
- **Connection_Wizard**: A single-entry store-connection flow that performs both credential/authorization and store binding in one process, replacing the prior two-step "API connection then store connection" flow.
- **Amazon_Connection_Wizard**: The Connection_Wizard for the 亚马逊 block, performing one-step OAuth authorization plus store binding, reusing the existing `AmazonAdsConnectWizard`.
- **Independent_Site_Connection_Wizard**: The Connection_Wizard for the 独立站 block, accepting Shopify / WooCommerce / TikTok credentials in one place and binding the resulting Store; Google Ads is bound to an independent-site Store from within this flow.
- **GoogleAds_Module**: The Frontend workspace and Backend services for Google Ads, covering account connection, campaign list, performance reports, campaign creation, bid/budget/status adjustment, and AI hosting.
- **GoogleAdsConnector**: The existing read connector that pulls Google Ads campaigns and performance reports via GAQL.
- **GoogleAdsWriteConnector**: The existing write connector that submits Google Ads campaign create, bid, budget, and status changes.
- **Operation**: A single advertising write action modeled with an Operation_Source, an operationScope, and a Sync_State, routed through the Outbox to a platform Write_Connector (defined by `advertising-workspace-rework`).
- **Outbox**: The persisted pending-platform-submission table whose worker submits Operations to the platform outside any database transaction (defined by `advertising-workspace-rework`).
- **GoogleAds_Hosting_Engine**: The net-new Google-Ads-specific AI optimization engine that generates campaign create and bid/budget/status adjustment decisions from Google Ads performance data and emits candidate decisions to the platform-generic Optimization_Coordinator.
- **Optimization_Coordinator**: The platform-generic component (from `amazon-ads-ai-hosting-system`) that de-conflicts, prioritizes, clips to safety boundaries, and turns accepted candidate decisions into Operations.
- **Execution_Mode**: The per-store/goal/campaign mode governing whether AI decisions execute — `observe_only`, `recommend_only`, `approval_required`, or `auto_execute` (from `amazon-ads-ai-hosting-system`).
- **Effect_Attribution**: The post-execution measurement comparing key metrics before vs. after an Operation took effect (from `amazon-ads-ai-hosting-system`).
- **Customer_Ticket**: A support ticket created from a buyer message, surfaced under the 亚马逊 block.
- **Ticket_AI_Assistant**: The AI capability that drafts replies, classifies, and suggests handling actions for a Customer_Ticket, subject to human review/confirmation.
- **RBAC_Model**: The extended access-control model comprising the three independently assignable dimensions below, layered on the existing `permissions` / `roles` / `data_scopes` tables.
- **Platform_Access**: The first RBAC dimension — which Nav_Blocks (亚马逊 / 独立站 / 物流 / 财务统计) an account may enter.
- **Store_Group_Scope**: The second RBAC dimension (Store-Group Data Scope) — which Store_Groups' Stores an account may read and operate. Data outside an account's assigned Store_Groups is neither visible nor operable.
- **Functional_Permission**: The third RBAC dimension — operation-level permissions in the existing `module:action` format (for example advertising, product, customer, finance, warehouse), extended with 亚马逊 and 独立站 operation sub-dimensions.
- **Super_Administrator**: The role that bypasses all RBAC_Model restrictions and may perform every operation across all Store_Groups and all Nav_Blocks (the existing super-administrator role from `core-platform-completion`).
- **Permission_Service**: The Backend authorization component (from `core-platform-completion`) that verifies a caller holds a required Functional_Permission.
- **Data_Scope_Service**: The Backend component (from `core-platform-completion`) that restricts records to the caller's effective data scope, extended here with Store_Group_Scope.
- **Cross_Group_Access**: Any attempt by an account to read or operate a Store, or data belonging to a Store, whose Store_Group is not within the account's assigned Store_Group_Scope.
- **Cross_Platform_Access**: Any attempt by an account to enter a Nav_Block or invoke an operation in a platform family the account's Platform_Access does not include.
- **Button_Audit**: The acceptance activity of inspecting every Frontend button/action for correct front-to-back wiring (click response, correct endpoint invocation, error feedback) and recording the result.

## Requirements

### Requirement 1: Four-Block Two-Level Navigation

**User Story:** As an operator, I want the left navigation collapsed into four top-level blocks with expandable submenus, so that I can find modules quickly instead of scanning twelve flat groups.

#### Acceptance Criteria

1. THE Navigation_Shell SHALL render exactly four Nav_Blocks in this order: 亚马逊, 独立站, 物流, 财务统计.
2. WHEN an operator clicks a Nav_Block header, THE Navigation_Shell SHALL toggle that Nav_Block between expanded and collapsed, displaying the Nav_Block's second-level Nav_Items below the header while expanded and hiding them while collapsed.
3. WHILE a Nav_Block is expanded, THE Navigation_Shell SHALL display the Nav_Block's second-level Nav_Items in their defined order.
4. THE Navigation_Shell SHALL allow more than one Nav_Block to be expanded at the same time.
5. WHEN an operator selects a Nav_Item, THE Navigation_Shell SHALL navigate to that Nav_Item's route and indicate the selected Nav_Item as active.
6. WHEN the Frontend reloads after an operator has expanded or collapsed Nav_Blocks, THE Navigation_Shell SHALL restore the operator's last expand/collapse state for each Nav_Block.
7. WHERE a Nav_Block contains no Nav_Item the logged-in account is permitted to see (per Requirements 12 and 14), THE Navigation_Shell SHALL hide that Nav_Block entirely.

### Requirement 2: Module Assignment Within Nav_Blocks

**User Story:** As an operator, I want each module placed under the correct block, so that the four-block structure reflects how I actually work.

#### Acceptance Criteria

1. THE 亚马逊 Nav_Block SHALL contain the Nav_Items: 亚马逊店铺连接, 经营看板, 今日待办, 审批中心, 广告 (全部搜索广告, 广告目标, AI优化建议, AI广告监控, 广告组合, 智能诊断, AI通知, 自动化规则, 广告位锁定, 关键词, 词库, 搜索词, 关键词智能, 排名监控, 创意素材, Insight Agent), 商品 (商品管理, AI商品内容), 订单, 退货, 退款, 买家消息, 客服工单, Review, Feedback, and AMC数据工作室.
2. THE 独立站 Nav_Block SHALL contain the Nav_Items: 独立站店铺连接, Google Ads (连接, 列表, 报告, 创建, 调价, AI托管), 独立站订单, 独立站商品, and 渠道商品发布.
3. THE 物流 Nav_Block SHALL contain the Nav_Items: 库存健康, 补货建议, 仓库管理, FBA货件, 采购订单, and 供应商.
4. THE 财务统计 Nav_Block SHALL contain the Nav_Items: 利润看板, SKU利润, 结算管理, 报表中心, 数据洞察, and 数据质量.
5. THE Navigation_Shell SHALL map each Nav_Item to an existing Frontend route, and SHALL NOT render a Nav_Item whose route does not resolve.
6. THE Navigation_Shell SHALL associate each Nav_Item with a Platform_Access dimension matching its Nav_Block's platform family, so that platform-scoped filtering (Requirement 12) applies consistently.

### Requirement 3: Account-Area System Utilities

**User Story:** As an operator, I want system-level utilities outside the four blocks, so that the four blocks stay focused on day-to-day operations.

#### Acceptance Criteria

1. THE Navigation_Shell SHALL place 系统设置, 飞书机器人, 审计回滚, and CSV导入 in the Account_Area and SHALL NOT render them as Nav_Items inside any Nav_Block.
2. WHEN an operator opens the Account_Area, THE Navigation_Shell SHALL display the Account_Area utilities for which the logged-in account holds the required Functional_Permission.
3. WHERE the logged-in account lacks the Functional_Permission required for an Account_Area utility, THE Navigation_Shell SHALL hide that utility.
4. WHEN an operator selects an Account_Area utility, THE Navigation_Shell SHALL navigate to that utility's route.

### Requirement 4: Amazon One-Step Store Connection

**User Story:** As an operator, I want a single Amazon store-connection entry that authorizes and binds a store in one step, so that I no longer create an API connection and a store connection separately.

#### Acceptance Criteria

1. THE 亚马逊 Nav_Block SHALL expose a single 亚马逊店铺连接 entry as the only Amazon connection entry point.
2. WHEN an operator starts the Amazon_Connection_Wizard, THE System SHALL perform OAuth authorization and Store binding within one continuous flow, reusing the existing `AmazonAdsConnectWizard`.
3. WHEN OAuth authorization completes successfully, THE System SHALL create or update the Store's Platform_Connection and bind it to the resulting Store without requiring a separate store-connection step.
4. IF OAuth authorization fails or is cancelled, THEN THE System SHALL leave any existing Platform_Connection unchanged and present the failure reason.
5. WHEN an Amazon Store is connected through the Amazon_Connection_Wizard, THE System SHALL assign the Store to an Amazon Store_Group selected or created during the flow (Requirement 10).
6. THE System SHALL NOT require an operator to visit a separate "API 连接" page to complete an Amazon Store connection.

### Requirement 5: Independent-Site One-Step Store Connection

**User Story:** As an operator, I want a single independent-site store-connection entry for Shopify, WooCommerce, and TikTok, so that I can connect a store by entering credentials in one place.

#### Acceptance Criteria

1. THE 独立站 Nav_Block SHALL expose a single 独立站店铺连接 entry as the only independent-site connection entry point.
2. WHEN an operator selects a platform (Shopify, WooCommerce, or TikTok) in the Independent_Site_Connection_Wizard and submits the required credentials, THE System SHALL create the Platform_Connection and bind the resulting Store within one flow.
3. IF submitted credentials are rejected by the external platform, THEN THE System SHALL leave any existing Platform_Connection unchanged and present the rejection reason.
4. WHEN an independent-site Store is connected, THE System SHALL assign the Store to the independent-site Store_Group system (Requirement 10).
5. WHEN an operator binds Google Ads from within the Independent_Site_Connection_Wizard, THE System SHALL associate the Google Ads connection with a selected independent-site Store rather than create a standalone ad-only connection.
6. THE System SHALL NOT require an operator to visit a separate "API 连接" page to complete an independent-site Store connection.

### Requirement 6: Google Ads Campaign and Report Retrieval

**User Story:** As an independent-site operator, I want to see my Google Ads campaigns and performance reports in AdPilot, so that I can monitor Google Ads alongside the rest of the platform.

#### Acceptance Criteria

1. WHERE an independent-site Store has an active Google Ads Platform_Connection, THE GoogleAds_Module SHALL retrieve that Store's Google Ads campaigns through the existing GoogleAdsConnector.
2. WHERE an independent-site Store has an active Google Ads Platform_Connection, THE GoogleAds_Module SHALL retrieve that Store's Google Ads performance reports (impressions, clicks, cost, conversions, conversion value) through the existing GoogleAdsConnector.
3. WHEN an operator opens the Google Ads 列表 view, THE GoogleAds_Module SHALL display the retrieved campaigns with their name, status, budget, and key performance metrics.
4. WHEN an operator opens the Google Ads 报告 view, THE GoogleAds_Module SHALL display the retrieved performance metrics for the selected date range.
5. IF retrieval of Google Ads campaigns or reports fails, THEN THE GoogleAds_Module SHALL present an error indicator with a retry control and SHALL leave any previously displayed data unchanged.
6. WHERE the selected Store has no active Google Ads Platform_Connection, THE GoogleAds_Module SHALL present a connect prompt rather than an error.

### Requirement 7: Google Ads Manual Operations

**User Story:** As an independent-site operator, I want to create Google Ads campaigns and adjust bids, budgets, and status from AdPilot, so that I can manage Google Ads without leaving the platform.

#### Acceptance Criteria

1. WHEN an operator submits a Google Ads campaign creation, THE System SHALL create a `platform_mutation` Operation with Operation_Source `manual` and route it through the Outbox to the GoogleAdsWriteConnector.
2. WHEN an operator submits a Google Ads bid, budget, or status change, THE System SHALL create a `platform_mutation` Operation with Operation_Source `manual` and route it through the Outbox to the GoogleAdsWriteConnector.
3. WHEN the GoogleAdsWriteConnector accepts a submission, THE System SHALL advance the Operation through its Sync_States and record the platform response in the audit trail.
4. IF the GoogleAdsWriteConnector rejects a submission, THEN THE System SHALL record the failure reason and leave the affected internal record unchanged.
5. WHERE an operator lacks the independent-site advertising Functional_Permission, THE System SHALL reject the create or adjust request with an HTTP 403 status and SHALL NOT create an Operation.
6. WHILE a Google Ads change Operation is in an unsettled state, THE GoogleAds_Module SHALL display the pending change alongside the platform-confirmed value through the existing Pending_Overlay.

### Requirement 8: Google Ads AI Hosting

**User Story:** As an independent-site operator, I want AI to host my Google Ads the same way it hosts Amazon ads, so that campaign creation and bid/budget adjustments are optimized automatically within guardrails.

#### Acceptance Criteria

1. THE GoogleAds_Hosting_Engine SHALL generate Google Ads campaign-create and bid/budget/status-adjustment candidate decisions from Google Ads performance data over the resolved personality policy's lookback window.
2. THE GoogleAds_Hosting_Engine SHALL emit candidate decisions to the platform-generic Optimization_Coordinator rather than create Operations directly.
3. WHEN the Optimization_Coordinator accepts a GoogleAds_Hosting_Engine candidate, THE System SHALL create a `platform_mutation` Operation with Operation_Source `ai_hosting`, recording the before value, after value, the decision snapshot, and a structured decision explanation, reusing the platform-generic decision storage.
4. THE GoogleAds_Hosting_Engine SHALL respect the resolved Execution_Mode: under `observe_only` or `recommend_only` no Operation is submitted to Google Ads, and under `approval_required` or `auto_execute` decisions route through the platform-generic approval and execution pipeline.
5. THE GoogleAds_Hosting_Engine SHALL clamp every proposed change within the resolved Safety_Boundary before emitting a candidate.
6. WHEN a Google Ads AI Operation becomes effective, THE System SHALL run Effect_Attribution for that Operation reusing the platform-generic attribution surface.
7. THE GoogleAds_Hosting_Engine SHALL reuse the platform-generic Outbox write-back, decision storage, approval routing, and Effect_Attribution rather than introduce parallel implementations.
8. WHERE Google Ads performance data fails the data-quality freshness or completeness check, THE GoogleAds_Hosting_Engine SHALL skip optimization for the affected campaign and record the skip reason.

### Requirement 9: Customer Tickets Under Amazon With AI Assistance

**User Story:** As a customer-service operator, I want tickets under the Amazon block with AI help drafting and classifying them, so that I can respond faster while keeping human control.

#### Acceptance Criteria

1. THE Navigation_Shell SHALL place 客服工单 under the 亚马逊 Nav_Block.
2. WHEN a buyer message is converted into a Customer_Ticket, THE System SHALL associate the Customer_Ticket with the originating Store and its Store_Group.
3. WHEN an operator requests AI assistance for a Customer_Ticket, THE Ticket_AI_Assistant SHALL produce a draft reply, a suggested classification, and a suggested handling action for that ticket.
4. THE Ticket_AI_Assistant SHALL present its draft reply, classification, and suggested action as proposals requiring explicit operator confirmation before any reply is sent or status change is applied.
5. WHEN an operator confirms an AI-proposed reply or action, THE System SHALL apply it and record that the content originated from the Ticket_AI_Assistant in the audit trail.
6. IF AI assistance generation fails, THEN THE System SHALL leave the Customer_Ticket unchanged and present the failure reason.
7. WHERE an operator's Store_Group_Scope does not include the Customer_Ticket's Store_Group, THE System SHALL NOT display that Customer_Ticket to the operator (Requirement 13).

### Requirement 10: Store Group as a First-Class Entity

**User Story:** As an administrator, I want every store to belong to a named store group, so that access and data isolation can be managed by group.

#### Acceptance Criteria

1. THE System SHALL maintain Store_Group records, each with a name of 1 to 100 characters and a platform family that is exactly one of `amazon` or `independent_site`.
2. THE System SHALL associate every Store with exactly one Store_Group.
3. WHEN an administrator creates a Store_Group with a valid name and platform family, THE System SHALL persist the Store_Group and return its generated identifier.
4. IF an administrator creates a Store_Group with an empty name, a name exceeding 100 characters, or a name identical to an existing Store_Group within the same platform family, THEN THE System SHALL reject the request and return an error indicating the naming conflict or limit violation.
5. WHEN an administrator reassigns a Store to a different Store_Group of the same platform family, THE System SHALL persist the new association and apply it to subsequent data-scope evaluations.
6. IF an administrator attempts to assign a Store to a Store_Group whose platform family does not match the Store's platform, THEN THE System SHALL reject the request and return an error indicating the platform-family mismatch.
7. WHERE a Store has no explicitly assigned Store_Group, THE System SHALL assign the Store to a default Store_Group for the Store's platform family.

### Requirement 11: Dynamic Store Groups

**User Story:** As an administrator, I want to add Amazon operations groups over time without code changes, so that the platform scales as the team grows.

#### Acceptance Criteria

1. THE System SHALL allow an administrator to create additional Amazon Store_Groups at runtime through an administration surface, with no fixed upper limit imposed in code.
2. THE System SHALL NOT hardcode the set of Amazon Store_Groups; the seeded groups (for example 亚马逊一组, 亚马逊二组) SHALL be ordinary records that an administrator may extend.
3. WHEN an administrator adds a new Amazon Store_Group, THE System SHALL make that Store_Group immediately available for Store assignment and for Store_Group_Scope assignment (Requirement 13) without requiring a redeploy.
4. THE System SHALL represent independent-site Stores within the independent-site Store_Group system distinct from the Amazon Store_Groups.

### Requirement 12: Platform Access Dimension

**User Story:** As an administrator, I want to control which blocks an account can enter, so that an account only works in the platforms it is responsible for.

#### Acceptance Criteria

1. THE RBAC_Model SHALL support a Platform_Access dimension assignable per account selecting any subset of the four Nav_Blocks (亚马逊, 独立站, 物流, 财务统计).
2. WHEN the Navigation_Shell renders, THE Navigation_Shell SHALL display only the Nav_Blocks included in the logged-in account's Platform_Access.
3. IF an account requests a route belonging to a Nav_Block outside the account's Platform_Access (Cross_Platform_Access), THEN THE System SHALL reject the request with an HTTP 403 status and THE Frontend SHALL present an access-denied indication.
4. WHEN the Backend receives an operation request scoped to a platform family outside the caller's Platform_Access, THE System SHALL reject the request with an HTTP 403 status before the operation executes.
5. WHERE an account's Platform_Access changes, THE Frontend SHALL re-render the Nav_Blocks according to the updated Platform_Access on the next permission fetch or navigation, without requiring re-login.

### Requirement 13: Store-Group Data Scope Dimension

**User Story:** As an administrator, I want to control which store groups an account can manage, so that operators only see and act on their own groups' stores and data.

#### Acceptance Criteria

1. THE RBAC_Model SHALL support a Store_Group_Scope dimension assignable per account selecting one or more Store_Groups.
2. WHEN an account lists Stores or store-scoped records, THE Data_Scope_Service SHALL return only records whose Store belongs to a Store_Group within the account's Store_Group_Scope.
3. IF an account requests a single Store or store-scoped record whose Store_Group is outside the account's Store_Group_Scope (Cross_Group_Access), THEN THE System SHALL reject the request with an HTTP 403 status.
4. IF an account attempts to create or modify a record whose Store's Store_Group is outside the account's Store_Group_Scope, THEN THE System SHALL reject the request with an HTTP 403 status and persist no changes.
5. WHEN an account opens the store switcher, THE Frontend SHALL display only Stores whose Store_Group is within the account's Store_Group_Scope.
6. THE Data_Scope_Service SHALL apply Store_Group_Scope through the shared data-scope query layer so that every store-scoped module enforces the same restriction.
7. WHERE an account holds multiple roles granting different Store_Group_Scopes, THE Data_Scope_Service SHALL grant the union of the Store_Groups permitted across those roles.

### Requirement 14: Functional Permission Dimension

**User Story:** As an administrator, I want to assign operation-level permissions including separate Amazon and independent-site advertising permissions, so that I can give each account exactly the functions it needs.

#### Acceptance Criteria

1. THE RBAC_Model SHALL express Functional_Permissions in the existing `module:action` code format and SHALL evaluate them through the existing Permission_Service.
2. THE RBAC_Model SHALL include distinct advertising-operation Functional_Permissions for the 亚马逊 and 独立站 platform families, so that an account may be granted Amazon advertising operations without independent-site advertising operations and vice versa.
3. THE RBAC_Model SHALL support assigning finance, warehouse/logistics, product, and customer-service Functional_Permissions to a single account independently of the advertising Functional_Permissions.
4. WHEN the Backend receives an operation request, THE Permission_Service SHALL verify the account holds the Functional_Permission required for that operation before it executes.
5. IF the account does not hold the required Functional_Permission, THEN THE System SHALL reject the request with an HTTP 403 status.
6. WHERE the logged-in account lacks the Functional_Permission required for an action control, THE Frontend SHALL hide or disable that action control.
7. THE three RBAC_Model dimensions (Platform_Access, Store_Group_Scope, Functional_Permission) SHALL be assignable independently, such that any combination of the three is expressible for a single account.

### Requirement 15: Super Administrator Bypass

**User Story:** As a super administrator, I want unrestricted access, so that I can administer the entire platform without per-dimension grants.

#### Acceptance Criteria

1. WHERE an account holds the Super_Administrator role, THE Permission_Service SHALL authorize every operation without requiring an explicit Functional_Permission grant.
2. WHERE an account holds the Super_Administrator role, THE Data_Scope_Service SHALL grant access to every Store across all Store_Groups without applying Store_Group_Scope restrictions.
3. WHERE an account holds the Super_Administrator role, THE Navigation_Shell SHALL display all four Nav_Blocks regardless of Platform_Access assignment.
4. WHERE an account holds the Super_Administrator role, THE System SHALL permit operations across all platform families without applying Platform_Access or Cross_Platform_Access restrictions.

### Requirement 16: Data Isolation Security Boundary

**User Story:** As a security administrator, I want data isolation enforced as a hard boundary, so that no non-administrator account can ever read or operate data outside its assigned store groups and platforms.

#### Acceptance Criteria

1. FOR ALL non-Super_Administrator accounts and FOR ALL store-scoped read endpoints, THE Data_Scope_Service SHALL return only records whose Store_Group is within the account's Store_Group_Scope, returning no record from any other Store_Group (Store-Group isolation invariant).
2. FOR ALL non-Super_Administrator accounts and FOR ALL store-scoped write endpoints, THE System SHALL reject any write targeting a record whose Store_Group is outside the account's Store_Group_Scope with an HTTP 403 status and SHALL persist no change (Cross_Group_Access rejection invariant).
3. FOR ALL non-Super_Administrator accounts and FOR ALL platform-scoped operations, THE System SHALL reject any operation in a platform family outside the account's Platform_Access with an HTTP 403 status (Cross_Platform_Access rejection invariant).
4. WHEN an account's Store_Group_Scope and a requested record's Store_Group are evaluated, THE Data_Scope_Service SHALL make the same allow/deny decision for read and for write on the same record (read/write isolation consistency).
5. THE System SHALL enforce isolation server-side independently of any Frontend filtering, so that an isolation decision does not rely on the Frontend hiding controls.
6. THE isolation invariants in criteria 1 through 4 SHALL be verified by correctness-property tests generating accounts, Store_Groups, Stores, and requests across group and platform boundaries.

### Requirement 17: All-Button Functional Inspection

**User Story:** As a release owner, I want every button and action audited for correct front-to-back wiring, so that no control silently fails.

#### Acceptance Criteria

1. THE Button_Audit SHALL cover every page reachable through the four Nav_Blocks and the Account_Area, enumerating each interactive button/action control on each page.
2. WHEN the Button_Audit inspects a button/action, THE Button_Audit SHALL verify that activating the control invokes the correct Backend endpoint and that the control responds to the activation.
3. IF a button/action invokes a missing, incorrect, or unhandled endpoint, or produces no response on activation, THEN THE Button_Audit SHALL record the control as broken with its page, control, and observed failure.
4. WHEN a Backend request triggered by a button/action fails, THE Frontend SHALL present an error indication to the operator rather than fail silently.
5. THE Button_Audit SHALL produce an enumerated list of broken controls, and THE System SHALL repair each enumerated broken control so that it invokes the correct endpoint and surfaces success or error feedback.

### Requirement 18: Backward-Compatible RBAC and Schema Migration

**User Story:** As an administrator, I want the new store-group and platform model to layer onto the existing RBAC tables, so that current users, roles, and data scopes keep working through the transition.

#### Acceptance Criteria

1. THE System SHALL add the Store_Group entity and the Store↔Store_Group association to `db/schema.sql` consistent with the project's single-source-of-truth schema approach, without removing the existing `data_scopes`, `user_stores`, or `permissions` tables.
2. WHEN the migration runs against existing data, THE System SHALL assign every existing Store to a Store_Group derived from the Store's existing `store_group` label and platform, defaulting to the platform-family default Store_Group when no label exists.
3. THE System SHALL preserve the existing `data_scopes` dimensions (`all_company`, `department`, `own`, `assigned_store`, `assigned_product`) and SHALL extend, not replace, the scope model with the Store_Group_Scope and Platform_Access dimensions.
4. WHERE an existing account has an `assigned_store` data scope, THE Data_Scope_Service SHALL continue to honor that account's store assignments alongside the new Store_Group_Scope evaluation.
5. WHERE an existing account has no Platform_Access assignment after migration, THE System SHALL apply a defined default Platform_Access so that no existing account loses all navigation until an administrator assigns the dimension explicitly.
6. THE migration SHALL be idempotent, such that running it more than once produces the same resulting Store_Group assignments and default dimension grants as running it once.
