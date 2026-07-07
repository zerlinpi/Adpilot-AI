export const pages = {
  // Command Center
  commandCenter: {
    title: '经营驾驶舱',
    subtitle: '全局业务概览与优先行动',
  },
  // Dashboard
  dashboard: {
    title: '数据仪表盘',
    subtitle: '核心 KPI 指标与趋势分析',
  },
  // Stores
  stores: {
    title: '店铺管理',
    subtitle: '管理已连接的电商平台店铺',
    emptyTitle: '暂无店铺',
    emptyDesc: '连接你的第一个 Amazon 店铺开始使用',
    connectStore: '连接店铺',
  },
  // Goals
  goals: {
    title: '广告目标',
    subtitle: '管理你的广告优化目标',
    emptyTitle: '暂无广告目标',
    emptyDesc: '创建你的第一个广告目标，系统将自动生成 Campaign 结构',
    createGoal: '新建目标',
  },
  // Create Goal
  createGoal: {
    title: '新建广告目标',
    subtitle: '4 步创建你的广告优化目标',
    step1: '选择目标类型',
    step2: '选择产品',
    step3: '配置目标参数',
    step4: '设置关键词与自动化',
  },
  // Goal Detail
  goalDetail: {
    title: '目标详情',
    notFound: '目标不存在',
    notFoundDesc: '该广告目标不存在或已被删除',
  },
  // Campaigns
  campaigns: {
    title: '广告活动',
    subtitle: '管理所有广告活动',
    emptyTitle: '暂无广告活动',
    emptyDesc: '创建广告目标后，系统会自动生成 Campaign 结构',
  },
  // Keywords
  keywords: {
    title: '关键词管理',
    subtitle: '管理所有广告关键词',
    emptyTitle: '暂无关键词',
    emptyDesc: '创建 Campaign 后，系统会自动生成关键词',
  },
  // Search Terms
  searchTerms: {
    title: '搜索词分析',
    subtitle: '分析买家搜索词并优化关键词策略',
    emptyTitle: '暂无搜索词数据',
    emptyDesc: '导入 Search Term Report 或等待数据同步',
  },
  // Keyword Intelligence
  keywordIntelligence: {
    title: '关键词智能中心',
    subtitle: 'AI 驱动的关键词健康分析与优化建议',
    emptyTitle: '暂无诊断数据',
    emptyDesc: '点击"运行分析"开始关键词健康诊断',
    runAnalysis: '运行分析',
  },
  // Recommendations
  recommendations: {
    title: 'AI 优化建议',
    subtitle: '基于数据分析的智能优化建议',
    emptyTitle: '暂无待处理建议',
    emptyDesc: '系统会根据广告数据自动生成优化建议',
    generate: '生成建议',
  },
  // Reports
  reports: {
    title: '报表中心',
    subtitle: '生成和查看业务报表',
    emptyTitle: '暂无报表',
    emptyDesc: '点击"生成报表"创建你的第一份报告',
    generateReport: '生成报表',
  },
  // Products
  products: {
    title: '产品管理',
    subtitle: '管理你的产品目录',
    emptyTitle: '暂无产品',
    emptyDesc: '添加你的第一个产品开始使用',
    addProduct: '添加产品',
  },
  // Listing AI
  listingAI: {
    title: 'AI 商品内容工作室',
    subtitle: '按渠道生成、评分和优化商品内容',
    generate: '生成内容',
    score: '评分',
    complianceCheck: '合规检查',
    saveDraft: '保存草稿',
    approve: '审批通过',
    exportJson: '导出 JSON',
  },
  // Product Upload
  productUpload: {
    title: '渠道产品发布中心',
    subtitle: '校验、审批和导出渠道商品资料',
    createUpload: '创建上传任务',
    validate: '校验',
    approve: '审批',
    exportData: '导出',
  },
  // CSV Imports
  csvImports: {
    title: 'CSV 导入中心',
    subtitle: '导入 Amazon Ads 报表数据',
    upload: '上传 CSV',
    preview: '预览数据',
    mapFields: '字段映射',
    validate: '校验数据',
    commit: '提交导入',
    emptyTitle: '暂无导入记录',
    emptyDesc: '上传你的第一个 CSV 文件开始导入',
  },
  // Data Quality
  dataQuality: {
    title: '数据质量中心',
    subtitle: '检测和修复数据质量问题',
    runCheck: '运行检查',
    emptyTitle: '暂无数据质量问题',
    emptyDesc: '数据质量良好，无需修复',
  },
  // Profit Dashboard
  profitDashboard: {
    title: '利润看板',
    subtitle: '追踪盈利能力、利润率与广告效率',
  },
  // Product Profit
  productProfit: {
    title: '产品利润',
    subtitle: '按产品细分的盈利详情',
  },
  // Inventory Health
  inventoryHealth: {
    title: '库存健康度',
    subtitle: '监控库存水平、缺货风险和积压预警',
  },
  // Replenishment
  replenishment: {
    title: '补货计划',
    subtitle: 'AI 生成的库存补货建议',
    generate: '生成补货计划',
    approve: '审批补货',
    cancel: '取消',
  },
  // Today's Actions
  todayActions: {
    title: '今日待办',
    subtitle: '今日高优先级运营任务',
    emptyTitle: '暂无待办事项',
    emptyDesc: '今日任务已全部完成',
  },
  // Tasks
  tasks: {
    title: '任务中心',
    subtitle: '管理所有运营任务',
    emptyTitle: '暂无任务',
    emptyDesc: '系统会根据业务数据自动生成任务',
    createTask: '创建任务',
  },
  // Approvals
  approvals: {
    title: '审批中心',
    subtitle: '管理待审批的操作请求',
    emptyTitle: '暂无待审批项',
    emptyDesc: '所有审批请求已处理完毕',
  },
  // Feishu Integration
  feishuIntegration: {
    title: '飞书机器人配置',
    subtitle: '配置飞书机器人实现消息推送和审批',
    connect: '连接飞书',
    testMessage: '发送测试消息',
  },
  // Audit & Rollback
  auditRollback: {
    title: '审计与回滚',
    subtitle: '查看操作日志和回滚执行记录',
  },
  // Users
  users: {
    title: '用户管理',
    subtitle: '管理系统用户',
    createUser: '创建用户',
  },
  // Roles
  roles: {
    title: '角色管理',
    subtitle: '管理系统角色和权限',
    createRole: '创建角色',
  },
  // Departments
  departments: {
    title: '部门管理',
    subtitle: '管理组织架构',
    createDepartment: '创建部门',
  },
  // Settings
  settings: {
    title: '系统设置',
    subtitle: '管理系统配置',
  },
  // Departments-related (existing)
  // ── SparkX AI advertising surfaces (Req 18–30) ──────────────────────
  // AI advertising home dashboard (Req 18)
  aiDashboard: {
    title: 'AI 广告首页',
    subtitle: '销售总览、AI 动作与 AI 使用概览',
    salesOverview: '销售总览',
    salesTrend: '销售趋势',
    aiActions: 'AI 动作',
    aiUsage: 'AI 使用',
    aiNotificationsSummary: 'AI 通知',
    granularityDay: '日',
    granularityWeek: '周',
    granularityMonth: '月',
    emptyTitle: '暂无广告数据',
    emptyDesc: '所选日期范围内暂无广告数据',
  },
  // All Search Ads workspace (Req 19)
  allSearchAds: {
    title: '全部搜索广告',
    subtitle: '管理广告活动、托管、投放与搜索词',
    emptyTitle: '暂无广告活动',
    emptyDesc: '当前筛选条件下没有匹配的广告活动',
    createCampaign: '新建广告活动',
    bulkAction: '批量处理',
    tabCampaigns: '广告活动',
    tabAiHosting: 'AI托管',
    tabAdGroups: '广告组',
    tabPromotedProducts: '推广商品',
    tabTargeting: '投放',
    tabNegativeTargeting: '否定投放',
    tabSearchTerms: '搜索词',
    tabOtherProducts: '购买的其他商品',
    tabBidAdjustments: '竞价调整',
    tabBudgetCaps: 'SP预算上限',
    tabOperationLog: '操作日志',
  },
  // Ad Portfolios (Req 20)
  adPortfolios: {
    title: '全部广告组合',
    subtitle: '管理广告组合的预算与表现',
    emptyTitle: '暂无广告组合',
    emptyDesc: '创建你的第一个广告组合来组织广告活动',
    createPortfolio: '新建广告组合',
    noBudgetCap: '无预算上限',
  },
  // AI Hosting (Req 21)
  aiHosting: {
    title: 'AI 托管',
    subtitle: '设置托管目标与目标 ACoS，让 AI 持续优化',
    assignHosting: '设置托管',
    removeHosting: '取消托管',
    aiManaged: 'AI入格',
    hostingGoal: '托管目标',
    targetAcos: '目标ACOS',
  },
  // Smart Diagnosis (Req 22)
  smartDiagnosis: {
    title: '智能诊断',
    subtitle: '诊断商品广告结构并给出优化建议',
    emptyTitle: '暂无诊断任务',
    emptyDesc: '为父 ASIN 创建你的第一个诊断任务',
    createTask: '新建诊断任务',
  },
  // AI Notifications (Req 23)
  aiNotifications: {
    title: 'AI 通知',
    subtitle: '处理 AI 提出的优化建议与目标修正',
    emptyTitle: '暂无待处理通知',
    emptyDesc: '当前分类下没有待处理项',
    pendingList: '待处理',
    closedList: '已结束',
    config: '前往AI通知配置',
    categoryCoreOps: '广告运营核心关注',
    categoryOneClick: '广告活动一键优化',
    categoryHighPotential: '发现高潜广告活动',
    categoryTargetCorrection: 'AI目标修正待确认',
  },
  // Insight Agent (Req 24)
  insightAgent: {
    title: 'Insight Agent',
    subtitle: '用自然语言提问，获取洞察与建议动作',
    promptPlaceholder: '输入你的数据问题或分析请求...',
    submit: '提交',
    premium: 'Premium 模式',
    selectSource: '选择分析来源',
    suggestedPrompts: '推荐问题',
  },
  // Ad Placement Lock (Req 26)
  placementLock: {
    title: '广告位锁定',
    subtitle: '通过卡位策略将广告稳定在目标广告位',
    emptyTitle: '暂无卡位策略',
    emptyDesc: '添加你的第一个卡位策略',
    addStrategy: '添加策略',
    tabStrategy: '策略管理',
    tabTasks: '任务管理',
    tabAms: 'AMS实时数据',
  },
  // Keyword Library (Req 27)
  keywordLibrary: {
    title: '关键词',
    subtitle: '管理词库与关键词推荐',
    tabLibrary: '词库',
    tabRecommendations: '关键词推荐',
    emptyTitle: '暂无词库',
    emptyDesc: '创建你的第一个词库',
    createLibrary: '创建词库',
    harvest: '收割',
    negate: '否定',
  },
  // Rank Monitoring (Req 28)
  rankMonitor: {
    title: '排名监控',
    subtitle: '监控关键词的自然排名与广告排名',
    emptyTitle: '暂无监控任务',
    emptyDesc: '添加你的第一个排名监控任务',
    createTask: '新建监控任务',
    quota: '配额',
    quotaExhausted: '配额已用尽，无法添加新的监控任务',
    organicRank: '自然排名',
    adRank: '广告排名',
  },
  // Creative Assets (Req 29)
  creativeAssets: {
    title: '创意素材',
    subtitle: '管理品牌创意素材库',
    emptyTitle: '暂无创意素材',
    emptyDesc: '上传你的第一个创意素材',
    upload: '上传素材',
    searchPlaceholder: '按名称、标签、ASIN 或创建人搜索...',
  },
  // Data Insights / SQP / AMC (Req 30)
  dataInsights: {
    title: '数据洞察',
    subtitle: '商品、品牌、市场与搜索词表现洞察',
    productList: '商品列表',
    brandMetrics: '品牌指标',
    marketInsights: '市场洞察',
    sqp: 'SQP分析',
    customReportQuota: '自定义报告配额',
    requiresActivation: '该功能需要激活后使用',
    emptyTitle: '暂无数据',
    emptyDesc: '所选条件下暂无数据',
  },
  amc: {
    title: 'AMC 数据工作室',
    subtitle: '分析模型模板与受众创建',
    models: 'AMC模型库',
    audiences: '用户受众创建',
    requiresActivation: 'AMC 功能需要账户激活后使用',
    emptyTitle: '暂无可用模板',
    emptyDesc: '激活 AMC 后即可使用模型与受众模板',
  },
  // Permissions (Req 4)
  permissions: {
    title: '权限管理',
    subtitle: '查看系统权限目录',
    emptyTitle: '暂无权限定义',
    emptyDesc: '系统中尚未定义任何权限',
  },
  // Login logs (Req 15)
  loginLogs: {
    title: '登录日志',
    subtitle: '查看登录尝试记录',
    emptyTitle: '暂无登录记录',
    emptyDesc: '尚无登录尝试记录',
  },
  // API connections (Req 13)
  apiConnections: {
    title: 'API 连接',
    subtitle: '管理平台连接与凭据',
    emptyTitle: '暂无连接',
    emptyDesc: '添加你的第一个平台连接',
    addConnection: '添加连接',
    test: '测试连接',
    disconnect: '断开连接',
  },
};
