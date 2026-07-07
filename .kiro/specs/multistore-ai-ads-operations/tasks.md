# Implementation Plan: 多店铺 AI 广告运营（multistore-ai-ads-operations）

## Overview

本计划把设计文档拆成可增量交付的编码任务。整体策略与设计一致：**复用优先、薄编排层、后端强制安全边界、外部写入只经 Operation-Outbox 异步提交**。任务从底层 schema/枚举扩展开始，逐步搭建后端编排端点与只读 VO，再接入前端弹窗与数据视图，最后补齐指引/README 与端到端巡检。后端属性测试沿用既有 jqwik 范式（见 `TableViewIsolationPropertyTest`），前端导航可见性属性用 fast-check（见 `navConfig.*.property.test.ts`）。

约定：
- 后端代码位于 `backend-java/src/main/java/com/adpilot/...`，测试位于 `backend-java/src/test/java/com/adpilot/...`。
- 前端代码位于 `frontend/src/app/...`。
- 标 `*` 的子任务为测试任务，可跳过以求更快的 MVP；核心实现任务不带 `*`。
- 每个属性测试以注释标注：`Feature: multistore-ai-ads-operations, Property {n}: {text}`，`@Property(tries = 100)` 起步。

## Tasks

- [x] 1. 基础扩展：TikTok 平台族与飞书账号归属
  - [x] 1.1 新增 `tiktok` 平台族枚举与数据库约束迁移
    - 在 `com.adpilot.modules.rbac.PlatformFamily` 增加 `TIKTOK("tiktok")` 枚举值
    - 在 `backend-java/db/schema.sql` 以幂等方式扩展 `account_platform_access.platform_family` 的 CHECK 约束以允许 `'tiktok'`（沿用文件内 `adpilot_*` 辅助过程风格）；`stores.platform_family` 为自由 VARCHAR 无需改约束
    - _Requirements: 5.1, 6.1_
  - [x] 1.2 为飞书集成新增账号归属列
    - 在 `backend-java/db/schema.sql` 幂等地为 `feishu_integrations` 增加 `owner_account_id CHAR(36) NULL` 列并经 `adpilot_create_index_if_missing` 建 `(owner_account_id, store_id)` 索引
    - 在 `FeishuIntegrationEntity`（既有飞书集成实体）增加 `ownerAccountId` 字段映射
    - _Requirements: 7.1_

- [x] 2. 单产品广告创建：输入契约与校验
  - [x] 2.1 创建请求/结果 DTO 与 VO
    - 在 `modules/advertising/dto` 新增 `ProductAdCampaignRequest`（`storeId`, `productId`/`parentAsin`, `budget`, `budgetType`, `personality`, `hostingEnabled`, `targetAcos`, `bidMin`, `bidMax`, `budgetMin`, `budgetMax`, `executionMode` 可选）并加 Bean Validation 注解
    - 在 `modules/advertising/vo` 新增 `ProductAdCampaignResultVo`（`campaignId`, `productId`/`parentAsin`, `executionMode`）
    - _Requirements: 1.2, 1.10_
  - [x] 2.2 实现编排服务的输入校验与执行模式解析
    - 新增 `ProductAdCampaignService` 接口与 `ProductAdCampaignServiceImpl`（`modules/advertising/service` + `service/impl`）
    - 校验预算、目标 ACoS、出价/预算上下限均为正且上限不小于下限，违反时抛 `BusinessException(400)` 指明字段，且在写入开始前拒绝；复用 `SafetyBoundaryValidator` 的 only-tighten 校验
    - 用 `ExecutionMode.parse(...)` 解析执行模式，`null`/空白/非法回落到 `ExecutionMode.DEFAULT`（`observe_only`）
    - _Requirements: 1.3, 1.4_
  - [x] 2.3 编写输入校验属性测试
    - **Property 1: 输入校验拒绝非法预算/边界**
    - **Validates: Requirements 1.4**
  - [x] 2.4 编写执行模式缺省解析属性测试
    - **Property 2: 执行模式缺省回落到 observe_only**
    - **Validates: Requirements 1.3**

- [x] 3. 单产品广告创建：编排、隔离与端点
  - [x] 3.1 实现单事务编排逻辑
    - 在 `ProductAdCampaignServiceImpl.createProductAd` 标注 `@Transactional`：经 `CampaignService.createCampaign` 建关键词广告活动 → 插入 `CampaignProductLinkEntity` → 当 `hostingEnabled` 为真时经 `HostingConfigService` 落 campaign 级 HostingConfig（含 `execution_mode`）并持久化 `SafetyBoundaryEntity` → 经 `OperationService.createOperation(CreateOperationCommand)` 落 `platform_mutation` Operation+Outbox，绝不在请求线程内调用亚马逊 API；返回 `ProductAdCampaignResultVo`
    - _Requirements: 1.5, 1.6, 1.7, 1.9, 1.10_
  - [x] 3.2 实现店铺范围与平台族隔离
    - 在编排服务内用 `DataScopeService.assertCanWrite(storeId)` 校验店铺范围，并比对目标店铺 `platform_family` 与声明族，不一致以 HTTP 403 拒绝且不持久化任何更改
    - _Requirements: 1.8_
  - [x] 3.3 创建编排控制器
    - 新增 `ProductAdCampaignController`（`POST /api/product-ads/campaign`），加 `@RequirePermission("advertising:manage")` 与 `@RequirePlatform(PlatformFamily.AMAZON)`，委派给 `ProductAdCampaignService`，统一包装为 `ApiResponse`
    - _Requirements: 1.1, 1.5_
  - [x] 3.4 编写编排结果一致性属性测试
    - **Property 3: 合法提交产生一致的编排结果**
    - **Validates: Requirements 1.5, 1.10**
  - [x] 3.5 编写托管/安全边界条件持久化属性测试
    - **Property 4: 托管条件下持久化托管配置与安全边界**
    - **Validates: Requirements 1.6**
  - [x] 3.6 编写提交原子性属性测试
    - **Property 5: 提交原子性（全有或全无）**
    - **Validates: Requirements 1.9**
  - [x] 3.7 编写外部写入异步化属性测试
    - **Property 6: 外部写入只经 Operation+Outbox 异步提交**（覆盖单产品广告创建与独立站回写两条路径，mock `PlatformWriteConnector` 断言请求线程内零同步调用）
    - **Validates: Requirements 1.7, 4.2**
  - [x] 3.8 编写写入范围与平台族隔离属性测试
    - **Property 7: 写入操作的店铺范围与平台族隔离**
    - **Validates: Requirements 1.8, 4.6, 6.5**

- [x] 4. 检查点 — 确保编排相关测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. 亚马逊广告数据可见性与产品关联
  - [x] 5.1 实现产品↔活动列出与表现数据映射
    - 扩展查询按 `campaign_product_links` 与 `CampaignEntity.parentAsin` 返回某产品（ASIN）关联活动；复用 `ProductAdService`/`performance_daily`/`advertised_product_report` 组装对外 VO，至少含花费、点击、订单、销售额、ACoS，并透传 `data_status`（`preliminary`/`finalized`）
    - _Requirements: 2.1, 2.2, 2.3_
  - [x] 5.2 实现同步可观测性只读端点
    - 新增 `ProductAdSyncStatusVo`（`storeId`, `reportType`, `reportStatus`, `lastSuccessAt`, `lastError`）与只读端点 `GET /api/product-ads/sync-status?storeId=`，从 `ReportSyncRunEntity`/`ReportSyncErrorEntity` 取最近成功时间、运行状态与可读失败原因；失败状态绝不映射为成功
    - _Requirements: 2.5, 2.6_
  - [x] 5.3 对读取应用数据范围隔离
    - 所有产品广告与表现数据读取经 `DataScopeService.applyScope` 注入店铺范围谓词，非超管只返回其 `Store_Group_Scope` 内店铺数据
    - _Requirements: 2.7_
  - [x] 5.4 编写产品↔活动关联完备性属性测试
    - **Property 11: 产品↔活动关联查询完备且不泄漏**
    - **Validates: Requirements 2.1**
  - [x] 5.5 编写表现数据与数据状态保真属性测试
    - **Property 12: 表现数据与数据状态保真映射**
    - **Validates: Requirements 2.2, 2.3**
  - [x] 5.6 编写同步失败永不成功属性测试
    - **Property 13: 同步失败永不映射为成功**
    - **Validates: Requirements 2.6**
  - [x] 5.7 编写店铺范围读取隔离属性测试
    - **Property 8: 店铺范围读取隔离**（覆盖产品广告/表现数据、店铺切换候选、飞书集成查看/管理，沿用 `TableViewIsolationPropertyTest` 的"按 scope 过滤存储 + 校验查询谓词含当前 scope 不含他者"手法）
    - **Validates: Requirements 2.7, 6.3, 6.4, 7.6**

- [x] 6. 独立站库存更新与发货回写
  - [x] 6.1 创建请求与连接状态 DTO/VO
    - 在 `modules/advertising`（或独立站相应模块）新增 `InventoryUpdateRequest`、`FulfillmentRequest` 与 `IndependentSiteConnectionStateVo`（`storeId`, `platform`, `connectionState`, `inventoryWriteSupported`, `fulfillmentWriteSupported`, `lastSuccessAt`）
    - _Requirements: 4.4, 4.7_
  - [x] 6.2 实现写回编排服务
    - 新增 `IndependentSiteWriteService` + 实现：用 `WriteCapabilityService.isWriteCapable(storeId)` 与 `shopify`/`woocommerce` 连接状态门控；有效连接时经 `OperationService.createOperation` 落 Operation+Outbox 异步回写，绝不在请求线程内同步调用外部平台；无有效连接/缺凭据（`not_authorized`）返回未授权（403/409）且不创建任何 Operation/Outbox、不记录已发生回写；回写失败时 Operation 置 `failed` 保留可读原因
    - _Requirements: 4.1, 4.2, 4.3, 4.5_
  - [x] 6.3 实现连接状态查询与能力边界标注及范围隔离
    - 实现 `connection-state` 逻辑返回每店铺连接状态与 `inventoryWriteSupported`/`fulfillmentWriteSupported` 能力标志（连接器未注册/能力缺失标为不支持）；写操作前经 `DataScopeService.assertCanWrite` + 平台族比对
    - _Requirements: 4.4, 4.6, 4.7_
  - [x] 6.4 创建独立站写回控制器
    - 新增 `IndependentSiteWriteController`：`POST /api/independent-site/products/{productId}/inventory`、`POST /api/independent-site/orders/{orderId}/fulfillment`、`GET /api/independent-site/connection-state`，各方法加 `@RequirePermission` 与 `@RequirePlatform(PlatformFamily.INDEPENDENT_SITE)`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.6, 4.7_
  - [x] 6.5 编写独立站写入连接门控属性测试
    - **Property 14: 独立站写入受连接状态门控且不可写时无副作用**
    - **Validates: Requirements 4.1, 4.3**
  - [x] 6.6 编写回写失败保留可读原因单元测试
    - 模拟 connector 拒绝，断言 Operation 置 `failed` 且保留可读失败原因
    - _Requirements: 4.5_

- [x] 7. 检查点 — 确保数据可见性与独立站回写测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. TikTok 导航块、后端门控与连接族隔离
  - [x] 8.1 新增 TikTok 导航块与平台族类型
    - 在 `frontend/src/app/lib/navConfig.ts` 的 `PlatformFamily` 类型增加 `'tiktok'`；在 `navBlocks` 末尾追加 `key: 'tiktok'` 块（含店铺连接入口 `to: '/data-sync?platform=tiktok'`），把 TikTok 连接入口从独立站块移出；`defaultExpandState()` 增加 `tiktok: true`
    - _Requirements: 5.1, 5.2, 5.3, 5.6_
  - [x] 8.2 抽出导航可见性纯函数
    - 在 `navConfig.ts`（或同级 helper）抽出/固化 `visibleNavBlocks(user)` 纯函数：块可见当且仅当账号 `Platform_Access` 含该块平台族且持有该项所需功能权限；前端每次从 `GET /api/auth/me` 拉取 `platformAccess` 而非依赖登录态缓存
    - _Requirements: 3.1, 3.2, 3.4, 5.4_
  - [x] 8.3 后端平台族门控对齐
    - 为 Google Ads 路由对应控制器方法加 `@RequirePlatform(PlatformFamily.INDEPENDENT_SITE)`、为 TikTok 路由方法加 `@RequirePlatform(PlatformFamily.TIKTOK)`，无权限 → 403，独立于前端是否隐藏
    - _Requirements: 3.3, 5.5_
  - [x] 8.4 强制块内同平台族连接创建
    - 在独立站/数据同步连接服务（`IndependentSiteConnectionService` / 连接入口处理）校验所建连接平台键属于当前块平台族（amazon 族 / independent_site 族 / tiktok 族），跨族平台键一律拒绝
    - _Requirements: 6.2_
  - [x] 8.5 编写后端平台族门控属性测试
    - **Property 9: 后端平台族门控**
    - **Validates: Requirements 3.3, 5.5**
  - [x] 8.6 编写导航可见性纯函数属性测试（fast-check）
    - **Property 10: 导航可见性是 Platform_Access 的纯函数**
    - **Validates: Requirements 3.1, 3.2, 3.4, 5.4**
  - [x] 8.7 编写块内连接同族约束属性测试
    - **Property 15: 块内连接只能创建同平台族连接**
    - **Validates: Requirements 6.2**

- [x] 9. 按账号、按店铺独立的飞书通知
  - [x] 9.1 绑定时写入账号归属并收紧解析
    - `POST /api/integrations/feishu/connect` 绑定时写入当前用户为 `owner_account_id`；收紧 `FeishuService` 解析：只选 `store_id` 匹配且 `owner_account_id` 匹配该店铺所属账号的集成，绝不跨账号/跨店铺复用，无匹配即不发送
    - _Requirements: 7.1, 7.2, 7.3_
  - [x] 9.2 强化无绑定优雅跳过与范围隔离
    - `HostingNotificationServiceImpl`/`pushAiNotification` 无目的地时返回 `false` 并记录可读跳过原因，不中断其他店铺通知；飞书集成查看/管理经 `DataScopeService` 限定在账号 `Store_Group_Scope` 内
    - _Requirements: 7.4, 7.6_
  - [x] 9.3 编写飞书通知隔离属性测试
    - **Property 16: 飞书通知按店铺与账号隔离**
    - **Validates: Requirements 7.2, 7.3**
  - [x] 9.4 编写测试连接端点单元测试
    - 验证 `POST /api/integrations/feishu/{id}/test-message` 用该绑定凭据返回连通性结果
    - _Requirements: 7.5_

- [x] 10. 检查点 — 确保 TikTok 门控与飞书隔离测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 11. 前端弹窗、数据视图与多店铺切换
  - [x] 11.1 新增 API 客户端函数
    - 在 `frontend/src/app/lib/api.ts` 新增 `createProductAdCampaign(req)`、产品广告同步状态查询、独立站 `connection-state` 查询函数，复用既有 `request<T>` 错误解包
    - _Requirements: 1.2, 2.5, 4.4_
  - [x] 11.2 构建单产品广告创建弹窗组件
    - 新增 `ProductAdModal`（产品/商品管理入口），仅在持有平台族访问与 `advertising:view`/`advertising:manage` 时渲染入口；采集预算、预算类型、AI 人格、`hosting_enabled`、安全边界、执行模式（可不选）；提交前做非正数/上限<下限的轻量校验并提交到 `POST /api/product-ads/campaign`
    - _Requirements: 1.1, 1.2, 1.3, 1.4_
  - [x] 11.3 构建产品广告数据视图
    - 以产品为视角展示其关联活动与表现指标、`preliminary`/`finalized` 数据状态标签、T+1/初步数据说明文案，以及同步状态（`last_success_at`、运行状态、失败原因）
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_
  - [x] 11.4 构建独立站库存/发货视图
    - 提供库存更新、发货标记入口与连接状态展示；当能力为不支持时把入口标注为"暂不支持"而非提供静默失败入口
    - _Requirements: 4.4, 4.7_
  - [x] 11.5 接入按平台族分组的多店铺切换控件
    - 店铺切换器按平台族分组，仅在当前 Nav_Block 平台族内切换，所选店铺不影响其他平台族视图
    - _Requirements: 6.4, 6.6_
  - [x] 11.6 编写弹窗校验前端单元测试
    - 测试非正数与上限<下限的即时提示及提交阻断
    - _Requirements: 1.4_

- [x] 12. 指引、README 与功能巡检
  - [x] 12.1 增加后台内分步操作指引
    - 在相关页面增加店铺连接、报表同步与产品广告数据、单产品广告创建、独立站回写、TikTok 连接、飞书发送等关键流程的分步引导文案，含亚马逊数据来源与 T+1/初步数据说明，并对"暂不支持"能力如实标注
    - _Requirements: 8.3, 8.4, 8.6_
  - [x] 12.2 更新 README
    - 更新 `README.md`：能力概览、各 Nav_Block（含 TikTok）用途、各平台族连接方式、单产品广告创建与飞书配置指引、当前限制
    - _Requirements: 8.5, 8.6_
  - [x] 12.3 编写关键流程集成/冒烟测试
    - 以自动化集成测试覆盖店铺连接、报表同步与产品广告数据展示、单产品广告创建提交、独立站回写、TikTok 连接、飞书发送，逐项记录通过/失败与可读原因
    - _Requirements: 8.1, 8.2_

- [x] 13. 最终检查点 — 确保全部测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- 标 `*` 的子任务为可选测试任务，可为更快的 MVP 跳过；核心实现任务绝不标可选。
- 每个任务引用具体需求条款以保证可追溯性。
- 属性测试覆盖设计中的 16 条全称属性，单元/集成测试覆盖具体示例、边界与端到端连通性。
- Property 6、7、8 为跨端点共享的全称属性，分别在单产品广告与独立站路径上各取代表性输入即可。
- 隔离类属性（7、8、9、16）沿用 `TableViewIsolationPropertyTest` 的手法：既验证返回结果隔离、又验证下发查询谓词含当前 scope 而不含他者。

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "2.1", "6.1", "8.1"] },
    { "id": 1, "tasks": ["2.2", "5.1", "5.2", "6.2", "8.2", "9.1", "11.1"] },
    { "id": 2, "tasks": ["2.3", "2.4", "3.1", "5.3", "6.3", "8.3", "8.4", "9.2"] },
    { "id": 3, "tasks": ["3.2", "3.3", "5.4", "5.5", "5.6", "6.4", "9.3", "9.4", "11.2", "11.3", "11.4"] },
    { "id": 4, "tasks": ["3.4", "3.5", "3.6", "3.7", "3.8", "5.7", "6.5", "6.6", "8.5", "8.6", "8.7", "11.5", "11.6", "12.1", "12.2"] },
    { "id": 5, "tasks": ["12.3"] }
  ]
}
```
