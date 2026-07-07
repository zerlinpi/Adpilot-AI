# Requirements Document

## Introduction

本规格（多店铺 AI 广告运营，`multistore-ai-ads-operations`）面向一个非技术的业务负责人愿景：在一个后台里用 AI 监控并管理多个店铺的广告，针对**单个产品**直接创建关键词广告，把"亚马逊广告数据怎么来、长什么样、和产品怎么关联"讲清楚；让独立站（WordPress/WooCommerce 或 Shopify）的 Google 广告按权限显示，并在有 API 的前提下回写库存与发货；把 TikTok 单独成块并明确其能力边界；保证不同渠道、不同店铺之间互不干扰；让飞书通知按账号、按店铺独立发送；最后巡检现有功能、细化操作指引并优化 README。

**这是一个"扩展/复用"规格，不是"重建"规格。** 平台已经实现了本愿景的大部分底层能力。每条需求都标注它**复用（reuses）**哪个既有组件、**扩展（extends）**哪个既有能力，避免与下列既有规格重复其范围：`amazon-ads-ai-hosting-system`、`advertising-workspace-rework`、`platform-workspace-rbac`、`platform-ux-logistics-enhancements`、`core-platform-completion`。本规格只补齐"以产品为入口的广告创建弹窗、数据可见性讲清楚、独立站回写、TikTok 独立菜单、渠道与飞书隔离、巡检与指引"这几块尚未成形的连接层。

### 亚马逊广告数据是怎么来的（用大白话讲清楚数据流）

很多使用者不清楚"亚马逊广告数据"从哪冒出来。它**不是**实时拉一个表就有的，而是走一条异步的报表生命周期（已由 `hosting` 子系统实现）：

1. **下单要报表（create）**：系统按报表类型（活动级 `SP_CAMPAIGN`、关键词级 `SP_KEYWORD`、搜索词级 `SP_SEARCH_TERM`）向亚马逊广告 API 提交一个"生成报表"的请求。
2. **轮询等待（poll）**：报表不是马上就好，系统按 `ReportSyncJob` 定时轮询报表状态，直到亚马逊把它标记为"已完成"。
3. **下载并入库（download + ingest）**：完成后系统拿到一个（通常是压缩的）下载地址，下载、解压、校验，然后幂等地写入本地数据库。
4. **落到两张表**：活动/关键词/天的指标进入 `performance_daily`（花费、点击、订单、销售额、ACoS 等按天聚合）；按 ASIN 的广告表现进入 `advertised_product_report`。
5. **和产品关联**：通过 `campaign_product_link`（活动↔产品）与 `CampaignEntity.parentAsin`（活动的父 ASIN）把这些广告数据挂回到具体产品上，使用者就能"站在一个产品上看它有哪些广告、花了多少、效果如何"。

一个关键认知：亚马逊报表通常是 **T+1**（隔天才齐），且当天/近几天的数据是"初步值（preliminary）"，会因归因延迟而被修订；只有结算后才算"最终值（finalized）"。本规格要求把这一点在界面上讲清楚，避免使用者误以为是实时数据。

### 数据隔离是安全边界

本规格中标注为 **【安全边界】** 的需求（渠道/平台族隔离、店铺组隔离、按账号/店铺的飞书隔离）属于安全不变量：它们必须由后端强制执行，不能依赖前端隐藏控件。这些边界复用并依赖 `platform-workspace-rbac` 的 `Platform_Access` / `Store_Group_Scope` / `Data_Scope_Service` 与 `HostingOrgIsolationGuard`。

## Glossary

- **System / 系统**: 本规格涉及的 AdPilot 后台整体（前端导航壳 + 后端服务）。当某条需求需要更具体的子系统名时，使用下列定义的具名组件。
- **Product_Ad_Modal / 单产品广告弹窗**: 从产品（商品管理/产品详情）入口打开的弹窗，用于为该产品创建一个关键词广告活动，并一次性配置预算、AI 人格、是否托管、安全边界、执行模式。新增的前端组件 + 后端编排端点。
- **CampaignBuilderService**: 既有的广告活动创建服务（复用），由 `CampaignCreateRequest` 驱动，经 `CampaignController` 暴露。
- **CampaignProductLink / 活动产品关联**: 既有的 `campaign_product_link` 表与其 Entity/Mapper（复用），表示某广告活动服务于某产品（ASIN）。
- **HostingConfig / 托管配置**: 既有 `HostingConfigEntity`/`HostingConfigService`（复用），保存某店铺/目标/活动的托管开关、执行模式与相关参数，由 `CampaignHostingRequest` 驱动。
- **AI_Personality / AI 人格**: 既有 `PersonalityPolicyEntity` + `AiPersonality` + `PersonalityResolver`（复用），决定 AI 优化的风格（如保守/均衡/激进）。
- **Safety_Boundary / 安全边界**: 既有 `SafetyBoundaryEntity` + `SafetyBoundary` + `SafetyBoundaryResolver`（复用），定义目标 ACoS、出价上下限、预算上下限等硬约束，采用"只收紧（only-tighten）"的四级继承（活动 > 目标 > 店铺 > 系统）。
- **Execution_Mode / 执行模式**: 既有 `ExecutionMode` 枚举（复用）：`observe_only`（仅观察）/ `recommend_only`（仅建议）/ `approval_required`（需审批）/ `auto_execute`（自动执行）。新店铺默认 `observe_only`。
- **Operation_Outbox_WriteBack / 操作-发件箱回写**: 既有的 Operation 状态机 + Outbox 模式（复用），所有对外部平台的写入都先落 Operation 与 outbox，由 `OutboxWorker` 异步提交，绝不在请求线程内同步调用外部平台。
- **Report_Lifecycle / 报表生命周期**: 既有 `hosting` 子系统（复用）：`ReportLifecycleClient`、`ReportIngestionService`、`ReportSyncJob`、`ReportType`，按"创建→轮询→下载→入库"拉取亚马逊广告报表。
- **Performance_Data / 广告表现数据**: `performance_daily`（活动/关键词/天指标）与 `advertised_product_report`（按 ASIN 广告表现）两张既有表（复用）。
- **Data_Status / 数据状态**: `performance_daily` 行上的标记，`preliminary`（初步、可能被修订）或 `finalized`（最终），用于在界面讲清 T+1 与归因延迟。
- **Sync_Observability / 同步可观测性**: 报表同步运行的状态（`ReportSyncRunEntity` 的 `last_success_at`、运行状态、错误 `ReportSyncErrorEntity`）对使用者可见的呈现（扩展项）。
- **Nav_Block / 导航块**: `navConfig.ts` 中的顶层导航块。当前为四块（亚马逊 / 独立站 / 物流 / 财务统计）；本规格新增 TikTok 为第五块。
- **Platform_Family / 平台族**: `Store_Group.platform_family` 与 `Nav_Block.key`，当前为 `amazon` / `independent_site` / `logistics` / `finance`；本规格新增 `tiktok`。
- **Platform_Access / 平台访问权限**: 既有 RBAC 维度（复用），决定某账号能进入哪些 Nav_Block。
- **Store_Group_Scope / 店铺组数据范围**: 既有 RBAC 维度（复用），决定某账号能读/操作哪些店铺组的店铺。
- **Independent_Site / 独立站**: WordPress/WooCommerce 站点或 Shopify 店铺。`platform_family = independent_site`。
- **Independent_Site_Write_Connector / 独立站写连接器**: 既有 `PlatformWriteConnector` 的独立站实现（复用/扩展），用于向 Shopify/WooCommerce 回写库存与发货。
- **Connection_State / 连接状态**: 某店铺对某平台的连接与写入状态：`not_authorized`（未授权/无凭据）、`syncing`（同步中）、`failed`（失败）、并带 `last_success_at`（最近成功时间）。
- **Feishu_Integration / 飞书集成**: 既有 `feishu_integrations` 表（复用），按 `storeId` + 账号 + `connectionType`（如 `app`）保存飞书应用凭据，经 `FeishuController` 的 connect/test 端点配置，受 `feishu:view` / `feishu:manage` 权限控制。
- **PlatformConnector.SUPPORTED**: 既有受支持平台键集合（复用）：`amazon_ads`、`amazon_sp_api`、`google_ads`、`shopify`、`tiktok_shop`、`woocommerce`。
- **Onboarding_Guidance / 操作指引**: 后台内的分步引导/帮助文案与 `README.md` 文档。
- **Functional_Inspection / 功能巡检**: 对关键端到端流程是否正常工作的验收检查活动。

## Requirements

### Requirement 1: 单产品 AI 广告创建弹窗（Per-product AI Ad Creation Modal）

**复用：** `CampaignBuilderService` / `CampaignCreateRequest` / `CampaignController`、`CampaignProductLink`、`HostingConfigService` + `CampaignHostingRequest`、`PersonalityResolver` + `AiPersonality`、`SafetyBoundaryResolver` + `SafetyBoundary`、`ExecutionMode`、Operation-Outbox 回写、`Data_Scope_Service`、`Platform_Access`。
**扩展：** 新增以产品为入口的弹窗前端组件，以及一个把"建活动 + 关联产品 + 落托管配置 + 落安全边界"打包提交的后端编排端点。

**User Story:** 作为一个不熟悉亚马逊广告操作的运营，我想在某个产品上点一下就弹出一个窗口、填几个能看懂的参数就创建出这个产品的关键词广告，这样我不需要理解底层广告结构也能开广告。

#### Acceptance Criteria

1. WHERE 登录账号的 Platform_Access 含有该产品所属店铺的 Platform_Family 且持有 `advertising:view` 与广告创建相关 Functional_Permission，THE System SHALL 在该产品的操作入口提供"创建广告"动作以打开 Product_Ad_Modal。
2. WHEN 使用者打开 Product_Ad_Modal，THE Product_Ad_Modal SHALL 展示并采集以下参数：预算金额、预算类型（`budget_type`）、AI 人格（AI_Personality）、是否托管（`hosting_enabled` 布尔）、安全边界（目标 ACoS 及出价上下限、预算上下限）、执行模式（Execution_Mode）。
3. WHEN 使用者未显式选择执行模式即提交，THE System SHALL 采用 `ExecutionMode.DEFAULT`（`observe_only`）作为该活动的执行模式。
4. IF 提交的预算、目标 ACoS 或出价/预算上下限为非正数或上限小于下限，THEN THE System SHALL 拒绝提交并返回指明具体字段的校验错误，且不创建任何活动、关联或配置。
5. WHEN 使用者提交合法的 Product_Ad_Modal 表单，THE System SHALL 经 `CampaignBuilderService` 创建一个关键词广告活动，并通过 CampaignProductLink 将该活动与该产品（ASIN）关联。
6. WHEN 活动创建成功且 `hosting_enabled` 为真，THE System SHALL 通过 HostingConfigService 持久化该活动的 HostingConfig（含 Execution_Mode），并持久化对应的 Safety_Boundary。
7. WHEN 提交触发对外部平台的写入，THE System SHALL 经 Operation_Outbox_WriteBack 创建 Operation 与 outbox 条目以异步提交，且 SHALL NOT 在请求线程内同步调用亚马逊广告 API。
8. 【安全边界】IF 提交所针对的店铺不在登录账号的 Store_Group_Scope 内，或其 Platform_Family 不在账号的 Platform_Access 内，THEN THE System SHALL 以 HTTP 403 拒绝该请求且不持久化任何更改。
9. IF 活动创建、产品关联、托管配置或安全边界在持久化过程中任一步失败，THEN THE System SHALL 不保留部分写入的结果，使该次提交对上述四类记录整体生效或整体不生效。
10. WHEN Product_Ad_Modal 成功提交，THE System SHALL 向使用者返回创建结果，包含新建活动标识、被关联的产品标识与该活动当前的 Execution_Mode。

### Requirement 2: 亚马逊广告数据可见性与产品关联（Amazon Ad Data ↔ Product Visibility）

**复用：** Report_Lifecycle（`ReportLifecycleClient`/`ReportIngestionService`/`ReportSyncJob`/`ReportType`）、`performance_daily`、`advertised_product_report`、`campaign_product_link`、`CampaignEntity.parentAsin`、`ProductAdService`/`ProductAdVo`、`ReportSyncRunEntity`/`ReportSyncErrorEntity`。
**扩展：** 以产品为视角聚合呈现其活动与表现数据；把报表同步生命周期的状态做成可观测呈现；用文案讲清 T+1 与初步/最终数据。

**User Story:** 作为一个不懂亚马逊广告数据从哪来的运营，我想在一个产品下面直接看到它有哪些广告、花了多少、效果怎样，并且知道这些数字是什么时候、以什么方式拉来的，这样我能放心地依据数据做决定。

#### Acceptance Criteria

1. WHEN 使用者查看某个产品的广告信息，THE System SHALL 通过 CampaignProductLink 与 `CampaignEntity.parentAsin` 列出与该产品关联的广告活动。
2. WHEN 使用者查看某个产品的广告表现，THE System SHALL 呈现来自 Performance_Data（`performance_daily` 与 `advertised_product_report`）的指标，至少包含花费、点击、订单、销售额与 ACoS。
3. WHERE 某条广告表现数据带有 Data_Status，THE System SHALL 在界面上标明该数据为初步（`preliminary`）或最终（`finalized`）。
4. THE System SHALL 在广告数据展示处提供说明文案，讲明亚马逊报表为隔天（T+1）生成、近几日数据为初步值并可能因归因延迟被修订。
5. WHEN 使用者查看数据同步状态，THE System SHALL 通过 Sync_Observability 呈现最近一次报表同步的成功时间（`last_success_at`）与当前运行状态。
6. IF 最近一次报表同步失败，THEN THE System SHALL 呈现失败状态并给出可读的失败原因，而非展示空白或伪造的成功状态。
7. 【安全边界】FOR ALL 非超级管理员账号，THE System SHALL 仅返回其 Store_Group_Scope 内店铺的广告与表现数据，不返回任何其他店铺组的数据。

### Requirement 3: 独立站 Google Ads 按权限显示（Independent-Site Google Ads Permission-Gated Visibility）

**复用：** `navConfig.ts`（独立站块中的 `google_ads/*` 项已按 `independent_site` Platform_Access + `advertising:view` 控制）、`PlatformAccessAspect`、`Data_Scope_Service`。
**扩展：** 将"有独立站权限才显示、否则不显示"形式化为前端渲染规则与后端访问规则的一致约束。

**User Story:** 作为平台管理员，我希望 Google 广告功能只对拥有独立站权限的账号显示，没有权限的账号完全看不到，这样界面清爽且不越权。

#### Acceptance Criteria

1. WHERE 登录账号的 Platform_Access 含有 `independent_site` 且持有 `advertising:view`，THE System SHALL 在独立站 Nav_Block 中显示 Google Ads 相关 Nav_Items。
2. WHERE 登录账号的 Platform_Access 不含 `independent_site` 或不持有 `advertising:view`，THE System SHALL NOT 渲染任何 Google Ads 相关 Nav_Item。
3. 【安全边界】IF 不满足第 1 条权限条件的账号请求任一 Google Ads 路由或后端操作，THEN THE System SHALL 以 HTTP 403 拒绝该请求，独立于前端是否隐藏控件。
4. WHERE 账号的 Platform_Access 发生变化，THE System SHALL 在下一次权限拉取或导航时按更新后的权限重新决定 Google Ads 项的可见性，无需重新登录。

### Requirement 4: 独立站库存更新与发货回写（Independent-Site Inventory Update & Fulfillment Write-Back）

**复用：** `IndependentSiteConnectionController`（`/independent-site/connect/store`）、`PlatformConnector.SUPPORTED`（`shopify`/`woocommerce`）、Independent_Site_Write_Connector（`PlatformWriteConnector`）、Operation-Outbox 回写、`Data_Scope_Service`。
**扩展：** 当存在 Shopify/WooCommerce 连接时，允许通过后台更新产品库存、标记发货/履约；显式暴露连接/写入状态；在无 API 或无凭据时绝不伪造成功。

**User Story:** 作为独立站运营，我想在这个后台里直接改库存、标发货，而不用去每个店铺后台操作；同时当某个店铺还没接 API 时，我希望系统老老实实告诉我不能操作，而不是假装成功。

#### Acceptance Criteria

1. WHERE 某独立站店铺存在状态为已连接的 Shopify 或 WooCommerce 连接，THE System SHALL 允许对该店铺的产品执行库存更新与发货/履约标记操作。
2. WHEN 使用者提交库存更新或发货标记，THE System SHALL 经 Operation_Outbox_WriteBack 通过 Independent_Site_Write_Connector 异步回写到对应平台，且 SHALL NOT 在请求线程内同步调用外部平台。
3. IF 目标店铺不存在有效连接或缺少所需凭据（`not_authorized`），THEN THE System SHALL 拒绝该操作并返回未授权状态，且 SHALL NOT 返回成功或记录已发生的回写。
4. THE System SHALL 为每个独立站店铺的写入能力呈现明确的 Connection_State：`not_authorized`、`syncing`、`failed`，以及最近成功时间 `last_success_at`。
5. IF 平台回写失败，THEN THE System SHALL 将该操作状态置为 `failed` 并保留可读的失败原因，供使用者重试或排查。
6. 【安全边界】IF 提交的店铺不在登录账号的 Store_Group_Scope 内或其 Platform_Family 不在 Platform_Access 内，THEN THE System SHALL 以 HTTP 403 拒绝且不持久化任何更改。
7. WHERE 某独立站平台的目标能力（库存或发货 API）尚不可用，THE System SHALL 在界面上将该能力标注为暂不支持，而非提供一个会静默失败的入口。

### Requirement 5: TikTok 独立菜单与隔离（TikTok as Its Own Nav_Block）

**复用：** `navConfig.ts` 的 Nav_Block 结构与 `PlatformFamily` 类型、`PlatformAccessAspect`、`TikTokConnector`（`tiktok_shop`）、连接入口的平台范围参数（`?platform=`）。
**扩展：** 新增第五个 Nav_Block（TikTok），把 TikTok 店铺与连接从独立站中移出，连接入口范围限定为 `tiktok`，并由独立的 Platform_Access（`tiktok`）控制；明确 TikTok 广告管理能力的支持范围。

**User Story:** 作为平台管理员，我不太熟悉 TikTok 的运行方式，希望把 TikTok 单独列成一个菜单、和独立站分开，并明确告诉我它现在能做什么、还不能做什么，这样我能把控风险。

#### Acceptance Criteria

1. THE System SHALL 在 navConfig 中新增一个 Platform_Family 为 `tiktok` 的 Nav_Block，作为第五个顶层导航块。
2. THE System SHALL 将 TikTok 店铺与 TikTok 连接入口归属到 `tiktok` Nav_Block，且 SHALL NOT 将其继续呈现在独立站（`independent_site`）Nav_Block 中。
3. THE System SHALL 将 TikTok 连接入口的平台范围限定为 `tiktok`（连接入口的 `platform` 参数为 tiktok 族），使其只创建 `tiktok_shop` 等 TikTok 族连接。
4. WHERE 登录账号的 Platform_Access 含有 `tiktok`，THE System SHALL 显示 TikTok Nav_Block；WHERE 不含 `tiktok`，THE System SHALL NOT 渲染 TikTok Nav_Block。
5. 【安全边界】IF Platform_Access 不含 `tiktok` 的账号请求 TikTok 路由或后端操作，THEN THE System SHALL 以 HTTP 403 拒绝。
6. THE System SHALL 在 TikTok Nav_Block 内明确标注当前已支持的能力（如店铺连接、订单/商品数据同步）与尚不支持的能力（如尚未提供的 TikTok 广告管理动作），避免呈现会静默失败的入口。

### Requirement 6: 店铺渠道隔离与精简多店铺管理（Channel Isolation & Streamlined Multi-Store Management）

**复用：** `Store_Group.platform_family`、`Platform_Access`、`Store_Group_Scope`、`Data_Scope_Service`、`HostingOrgIsolationGuard`、连接入口的 `platformFamily`/`?platform=` 范围。
**扩展：** 把"亚马逊连亚马逊、独立站连独立站、TikTok 连 TikTok"形式化为连接、可见性与店铺切换均按平台族隔离，并提供精简的多店铺切换体验。

**User Story:** 作为同时管多个渠道的运营，我希望每个渠道的店铺只在自己渠道的菜单下连接和显示、彼此互不干扰，而且店铺切换尽量简单，这样我不会把不同渠道的数据搞混。

#### Acceptance Criteria

1. THE System SHALL 使每个 Store 归属于唯一的 Platform_Family（`amazon` / `independent_site` / `tiktok` / 其他既有族）。
2. WHEN 使用者在某个 Nav_Block 内连接店铺，THE System SHALL 只允许创建与该块 Platform_Family 一致的连接，且 SHALL NOT 在该块内创建其他平台族的连接。
3. 【安全边界】FOR ALL 店铺范围的读取，THE System SHALL 仅返回与当前 Nav_Block 的 Platform_Family 一致且在账号 Store_Group_Scope 内的店铺数据，不返回其他平台族的店铺数据。
4. WHEN 使用者在某个 Nav_Block 内切换店铺，THE System SHALL 仅在该 Platform_Family 内的店铺之间切换，所选店铺不影响其他平台族的视图。
5. 【安全边界】IF 某次操作的目标店铺与其声明的 Platform_Family 不一致（跨族操作），THEN THE System SHALL 以 HTTP 403 拒绝该操作。
6. THE System SHALL 提供按平台族组织的店铺切换控件，使一个账号管理多个店铺时无需离开当前 Nav_Block 即可切换同族店铺。

### Requirement 7: 按账号、按店铺独立的飞书通知（Per-Account, Per-Store Feishu Notifications）

**复用：** `feishu_integrations` 表（`storeId` + 账号 + `connectionType`）、`FeishuController` connect/test 端点、`HostingNotificationService`/`HostingNotificationServiceImpl`、权限 `feishu:view`/`feishu:manage`、`Data_Scope_Service`。
**扩展：** 形式化"每个账号绑定自己的飞书应用、某店铺的通知只发到该账号为该店铺绑定的飞书会话"的隔离规则。

**User Story:** 作为多账号、多店铺的使用者，我希望每个账号连自己的飞书来发消息、每个店铺的通知只进它自己的群，这样不同账号、不同店铺的通知不会互相串。

#### Acceptance Criteria

1. WHERE 账号持有 `feishu:manage`，THE System SHALL 允许该账号为指定店铺绑定其自有的飞书应用凭据，按 `storeId` + 账号 + `connectionType` 保存到 Feishu_Integration。
2. WHEN 系统为某店铺发送通知，THE System SHALL 仅使用该店铺对应账号所绑定的 Feishu_Integration 发送，发送到该绑定指定的会话。
3. 【安全边界】THE System SHALL NOT 使用某账号的飞书凭据为该账号未绑定的店铺发送通知，亦 SHALL NOT 跨账号复用飞书凭据。
4. IF 某店铺没有有效的 Feishu_Integration，THEN THE System SHALL 跳过对该店铺的飞书发送并记录可读的跳过原因，而非报错中断其他通知或发到错误的会话。
5. WHEN 使用者在配置时点击测试连接，THE System SHALL 通过 `FeishuController` 的 test 端点用该绑定的凭据验证连通性并返回结果。
6. 【安全边界】FOR ALL 非超级管理员账号，THE System SHALL 仅允许查看与管理其 Store_Group_Scope 内店铺的 Feishu_Integration。

### Requirement 8: 现有功能巡检、指引细化与 README 优化（Functional Inspection, Onboarding Guidance & README）

**复用：** 既有各页面与端点、既有测试基线、`README.md`。
**扩展：** 增加一个验收维度，验证关键端到端流程可用；扩充后台内分步指引；优化 README。

**User Story:** 作为业务负责人，我想确认现有所有功能都还正常、新人能照着指引把活干完、README 能讲清楚怎么用，这样平台能真正被用起来。

#### Acceptance Criteria

1. THE Functional_Inspection SHALL 覆盖以下关键端到端流程并记录每项的通过/失败结果：店铺连接（各平台族）、亚马逊报表同步与产品广告数据展示、单产品广告创建弹窗提交、独立站库存/发货回写、TikTok 店铺连接、飞书通知发送。
2. IF Functional_Inspection 发现某关键流程不可用，THEN THE System SHALL 记录该失败项及可读原因，作为后续修复的依据。
3. THE Onboarding_Guidance SHALL 为第 1 条所列每个关键流程提供后台内的分步操作指引。
4. THE Onboarding_Guidance SHALL 用业务语言说明亚马逊广告数据的来源与 T+1/初步数据特性（与 Requirement 2 第 4 条一致）。
5. THE System SHALL 更新 `README.md`，使其包含项目能力概览、各 Nav_Block（含 TikTok）的用途、各平台族的连接方式，以及如何创建单产品广告与配置飞书通知的指引。
6. WHERE 某能力被标注为暂不支持（如部分 TikTok 广告管理、缺失的独立站回写 API），THE Onboarding_Guidance 与 README SHALL 如实说明其当前限制。
