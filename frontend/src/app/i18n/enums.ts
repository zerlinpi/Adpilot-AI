export const enums = {
  // Goal Status
  goalStatus: {
    active: '运行中',
    paused: '已暂停',
    completed: '已完成',
    archived: '已归档',
  },
  // Campaign Status
  campaignStatus: {
    active: '运行中',
    paused: '已暂停',
    learning: '学习期',
    limited_budget: '预算受限',
    needs_review: '需要检查',
  },
  // Entity Status
  entityStatus: {
    active: '运行中',
    paused: '已暂停',
    archived: '已归档',
  },
  // Risk Level
  riskLevel: {
    low: '低风险',
    medium: '中风险',
    high: '高风险',
    critical: '严重风险',
  },
  // Task Status
  taskStatus: {
    open: '待处理',
    in_progress: '处理中',
    waiting_approval: '待审批',
    completed: '已完成',
    dismissed: '已忽略',
    failed: '失败',
  },
  // Task Priority
  taskPriority: {
    low: '低',
    medium: '中',
    high: '高',
    urgent: '紧急',
  },
  // Recommendation Status
  recommendationStatus: {
    pending: '待处理',
    applied: '已应用',
    dismissed: '已忽略',
    watching: '观察中',
  },
  // Recommendation Action
  recommendationAction: {
    increase_bid: '提高出价',
    decrease_bid: '降低出价',
    increase_budget: '增加预算',
    decrease_budget: '降低预算',
    add_negative_keyword: '添加否定关键词',
    add_exact_keyword: '添加精准关键词',
    pause_target: '暂停投放目标',
    add_to_listing: '加入 Listing',
    create_replenishment_plan: '生成补货建议',
  },
  // Insight Segment
  insightSegment: {
    winner: '高转化词',
    waste: '浪费花费词',
    add_exact: '建议加精准',
    add_phrase: '建议加词组',
    add_broad: '建议加广泛',
    add_negative: '建议否定',
    watchlist: '观察中',
    brand: '品牌词',
    category: '类目词',
    competitor: '竞品词',
    long_tail: '长尾词',
    ranking: '排名机会词',
    listing_missing: 'Listing 缺失词',
  },
  // Insight Status
  insightStatus: {
    pending: '待处理',
    applied: '已应用',
    dismissed: '已忽略',
    watching: '观察中',
    ignored: '已忽略',
  },
  // Product Status
  productStatus: {
    active: '在售',
    paused: '已暂停',
    out_of_stock: '缺货',
    archived: '已归档',
  },
  // Store Status
  storeStatus: {
    connected: '已连接',
    disconnected: '未连接',
    error: '连接异常',
  },
  // Match Type
  matchType: {
    exact: '精准',
    phrase: '词组',
    broad: '广泛',
  },
  // Harvesting Status
  harvestingStatus: {
    candidate: '候选词',
    add_exact: '建议精准',
    add_phrase: '建议词组',
    add_broad: '建议广泛',
    add_negative: '建议否定',
    watchlist: '观察中',
    waste: '浪费词',
  },
  // Goal Type
  goalType: {
    launch: '新品冷启动',
    profit: '利润优化',
    growth: '放量增长',
    brand_defense: '品牌防守',
    competitor: '竞品截流',
    category: '类目拓展',
    clearance: '清库存',
    rank_boost: '排名提升',
  },
  // Campaign Type
  campaignType: {
    auto: '自动投放',
    manual_keyword: '手动关键词',
    pat: '商品投放',
    brand: '品牌投放',
    competitor: '竞品投放',
    category: '类目投放',
  },
  // Approval Status
  approvalStatus: {
    pending: '待审批',
    approved: '已同意',
    rejected: '已拒绝',
    expired: '已过期',
    executed: '已执行',
    failed: '执行失败',
  },
  // Import Status
  importStatus: {
    uploaded: '已上传',
    previewed: '已预览',
    mapping: '字段映射中',
    validating: '校验中',
    importing: '导入中',
    completed: '已完成',
    failed: '导入失败',
  },
  // Upload Job Status
  uploadJobStatus: {
    draft: '草稿',
    validating: '校验中',
    ready: '待审批',
    approved: '已审批',
    exported: '已导出',
    submitted: '已提交',
    success: '成功',
    failed: '失败',
    cancelled: '已取消',
  },
  // Listing Draft Status
  listingDraftStatus: {
    draft: '草稿',
    needs_review: '待审核',
    approved: '已审批',
    exported: '已导出',
    submitted: '已提交',
    failed: '失败',
  },
  // Inventory Risk
  inventoryRisk: {
    low: '低风险',
    medium: '中风险',
    high: '高风险',
  },
  // Automation Mode
  automationMode: {
    manual: '手动模式',
    approval: '审批模式',
    autopilot: '自动模式',
  },
  // Metrics
  metrics: {
    acos: '广告销售成本 ACoS',
    tacos: '总广告销售成本 TACoS',
    roas: '广告投入产出比 ROAS',
    ctr: '点击率 CTR',
    cvr: '转化率 CVR',
    cpc: '单次点击成本 CPC',
    spend: '广告花费',
    sales: '销售额',
    orders: '订单数',
    impressions: '曝光量',
    clicks: '点击量',
    grossProfit: '毛利润',
    netProfit: '净利润',
    netMargin: '净利润率',
    grossMargin: '毛利率',
    inventoryValue: '库存价值',
    daysOfSupply: '可售天数',
    breakEvenAcos: '盈亏平衡 ACoS',
    budgetUsage: '预算使用率',
  },
  // ── SparkX AI advertising enums (Req 18–30) ──────────────────────────
  // Campaign targeting status (Req 19.3)
  campaignTargetingStatus: {
    delivering: '投放中',
    paused: '已暂停',
    delivered: '已投放',
    reviewing: '审核中',
  },
  // Targeting type (Req 19.3)
  targetingType: {
    auto: '自动',
    manual: '手动',
  },
  // Ad type (Req 19.4)
  adType: {
    sp: 'SP',
    sb: 'SB',
    sd: 'SD',
  },
  // AI action types (Req 18.3)
  aiAction: {
    keyword_harvest: '关键词收割',
    negative_keyword: '关键词否定',
    bid_optimization: '竞价优化',
    budget_optimization: '预算优化',
    ad_structure_optimization: '广告结构优化',
    dayparting_budget: '分时段预算',
    dayparting_bid: '分时竞价',
    quick_search_test: '快搜测试',
  },
  // Hosting goal (Req 21)
  hostingGoal: {
    maximize_sales_at_target: '在目标效率下最大化销售',
    maximize_profit: '最大化利润',
    grow_sales: '放量增长',
  },
  // AI notification category (Req 23.1)
  aiNotificationCategory: {
    core_ops: '广告运营核心关注',
    one_click: '广告活动一键优化',
    high_potential: '发现高潜广告活动',
    target_correction: 'AI目标修正待确认',
  },
  // AI notification state (Req 23.2)
  aiNotificationState: {
    pending: '待处理',
    closed: '已结束',
    applied: '已应用',
    confirmed: '已确认',
    rejected: '已拒绝',
  },
  // Portfolio budget type (Req 20)
  portfolioBudgetType: {
    none: '无预算上限',
    recurring: '周期性预算',
    date_range: '日期范围预算',
  },
  // Keyword library type (Req 27.2)
  keywordLibraryType: {
    harvest: '收割词库',
    negative: '否定词库',
    brand: '品牌词库',
    competitor: '竞品词库',
  },
  // Rank type (Req 28)
  rankType: {
    organic: '自然排名',
    ad: '广告排名',
  },
  // Placement (Req 26.2)
  placement: {
    top_of_search: '首页1-1位',
    rest_of_search: '第1页5-8位',
    product_pages: '商品页面',
  },
};
