// AdPilot AI — React Query key convention (Req 3.8)
//
// Every read is assigned a Query_Key that is unique per backend resource and per
// store scope, expressed as a `[resource, storeId, ...params]` tuple. This keeps
// data for different resources or stores in separate cache entries, and lets write
// hosts invalidate an entire resource/store scope with a key prefix
// (`queryClient.invalidateQueries({ queryKey: [resource, storeId] })`, Req 3.3).
//
// Keys are returned `as const` so TypeScript preserves the tuple shape that
// react-query relies on for structural cache matching.

export const qk = {
  /** Campaigns list for a store, optionally filtered (Req 19.4). */
  campaigns: (storeId?: string, params?: unknown) => ['campaigns', storeId, params] as const,
  /** A single campaign by id. */
  campaignDetail: (id: string) => ['campaign', id] as const,
  /** Keywords (投放/关键词) for a store, derived from the advertising query state (Req 39). */
  keywords: (storeId?: string, params?: unknown) => ['keywords', storeId, params] as const,
  /** Search terms (搜索词) for a store, derived from the advertising query state (Req 39). */
  searchTerms: (storeId?: string, params?: unknown) => ['search-terms', storeId, params] as const,
  /** AI recommendations (优化建议) for a store, derived from the advertising query state (Req 39). */
  recommendations: (storeId?: string, params?: unknown) => ['recommendations', storeId, params] as const,
  /** Goals (广告目标) for a store. */
  goals: (storeId?: string, params?: unknown) => ['goals', storeId, params] as const,
  /** A single goal by id. */
  goalDetail: (id: string) => ['goal', id] as const,
  /** Campaign data-trend series for a store (Req 19.2). */
  campaignTrend: (storeId?: string, params?: unknown) => ['campaign-trend', storeId, params] as const,
  /** Ad portfolios for a store. */
  adPortfolios: (storeId?: string) => ['ad-portfolios', storeId] as const,
  /** Ad groups list for a store, optionally narrowed to one campaign. */
  adGroups: (storeId?: string, params?: unknown) => ['ad-groups', storeId, params] as const,
  /** Promoted products (推广商品) for a store. */
  productAds: (storeId?: string, params?: unknown) => ['product-ads', storeId, params] as const,
  /** Other purchased products (购买的其他商品) for a store. */
  otherProducts: (storeId?: string, params?: unknown) => ['other-products', storeId, params] as const,
  /** Negative keywords/targets (否定投放) for a store. */
  negativeKeywords: (storeId?: string, params?: unknown) => ['negative-keywords', storeId, params] as const,
  /** Bid adjustments (竞价调整) for a store. */
  bidChanges: (storeId?: string, params?: unknown) => ['bid-changes', storeId, params] as const,
  /** Operation log (操作日志) for a store. */
  operationLog: (storeId?: string, params?: unknown) => ['operation-log', storeId, params] as const,
  /** Hosting dashboard summary cards for a store (Req 11.1, 27.1). */
  hostingDashboardSummary: (storeId?: string) => ['hosting-dashboard-summary', storeId] as const,
  /** Recent AI hosting decisions for a store, newest first (Req 11.4). */
  hostingDecisions: (storeId?: string, params?: unknown) => ['hosting-decisions', storeId, params] as const,
  /** A single hosting decision's explanation detail (Req 11.5, 13). */
  hostingDecision: (id: string) => ['hosting-decision', id] as const,
  /** Shipments list for a store, optionally filtered. */
  shipments: (storeId?: string, params?: unknown) => ['shipments', storeId, params] as const,
  /** A single shipment's full detail (legs/cartons/customs/tracking/exceptions/cost-chain/FBA). */
  shipmentDetail: (id: string) => ['shipment', id] as const,
  /** Active_Store exception list, used by the shipments list to flag open exceptions (Req 9.6). */
  shipmentExceptionsByStore: (storeId?: string) => ['shipment-exceptions', storeId] as const,
  /** A shipment's ordered transport legs. */
  shipmentLegs: (id: string) => ['shipment', id, 'legs'] as const,
  /** A shipment's carton specs. */
  shipmentCartons: (id: string) => ['shipment', id, 'cartons'] as const,
  /** A shipment's computed carton totals. */
  shipmentCartonTotals: (id: string) => ['shipment', id, 'carton-totals'] as const,
  /** A shipment's customs clearance record. */
  shipmentCustoms: (id: string) => ['shipment', id, 'customs'] as const,
  /** A shipment's tracking trajectory (most-recent first). */
  shipmentTracking: (id: string) => ['shipment', id, 'tracking'] as const,
  /** A shipment's open/resolved exceptions. */
  shipmentExceptions: (id: string) => ['shipment', id, 'exceptions'] as const,
  /** A shipment's itemized cost chain + total. */
  shipmentCostChain: (id: string) => ['shipment', id, 'cost-chain'] as const,
  /** A shipment's FBA core fields + line items. */
  shipmentFba: (id: string) => ['shipment', id, 'fba'] as const,
  /** Org-scoped carrier directory (optionally narrowed by an org-scope discriminator). */
  carriers: (orgScope?: string) => ['carriers', orgScope] as const,
  /** Saved table views keyed by the host table's stable key. */
  savedViews: (tableKey: string) => ['saved-views', tableKey] as const,
  /** Per-user column configuration keyed by the host table's stable key. */
  columnConfig: (tableKey: string) => ['column-config', tableKey] as const,
} as const;

/**
 * The key tuples produced by {@link qk}. Useful when typing helpers that accept a
 * query key (e.g. a custom `useApiQuery` caller) without re-deriving each shape.
 */
export type QueryKey = ReturnType<(typeof qk)[keyof typeof qk]>;
