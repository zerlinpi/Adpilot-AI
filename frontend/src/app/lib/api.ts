// AdPilot AI — Frontend API Client

import { authFetch } from './auth';

const API_BASE = '/api';

interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: { code: string; message: string };
}

/**
 * Unwrap a backend {@link ApiResponse} envelope, surfacing an error indication
 * for ANY failure mode rather than resolving silently (Req 17.4, Property 23):
 *
 *  - Non-JSON body (HTML error page, empty body, proxy/gateway fallback): throw
 *    a readable status-line message, never the raw JSON parse error (Req 1.4).
 *  - Any non-2xx HTTP status (including 403 Cross_Platform_Access / Cross_Group
 *    access, Req 12.3) or a `success: false` envelope: throw the backend error
 *    message when present, otherwise a status-derived fallback.
 *
 * Every transport in this client (JSON requests and multipart uploads) funnels
 * through here so no error status can be swallowed.
 */
async function unwrapResponse<T>(res: Response): Promise<T> {
  let json: ApiResponse<T> | undefined;
  try {
    json = (await res.json()) as ApiResponse<T>;
  } catch {
    // The body was not valid JSON — e.g. an HTML error page from a proxy/gateway
    // fallback or an unhandled server error. Never surface the raw parse error
    // (e.g. "Unexpected token '<', \"<html>...\" is not valid JSON") to the user.
    // Build a readable message from the HTTP status line instead (Req 1.4).
    const statusText = res.statusText?.trim();
    throw new Error(`API error: ${res.status}${statusText ? ` ${statusText}` : ''}`);
  }

  if (!json || !json.success || !res.ok) {
    throw new Error(json?.error?.message || `API error: ${res.status}`);
  }
  return json.data as T;
}

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const url = `${API_BASE}${path}`;
  // authFetch attaches the Bearer token and redirects to /login on 401 (auth
  // expiry). A 403 (access denied) is NOT a logout — it flows back here so the
  // operator sees an access-denied error indication (Req 12.3).
  const res = await authFetch(url, options);
  return unwrapResponse<T>(res);
}

/**
 * Like {@link request} but always returns an array. Backend list endpoints are
 * inconsistent: some return a raw array, others a paginated object
 * ({ items, total, ... }). This normalizes both to an array so callers can map
 * safely without "x.map is not a function" crashes.
 */
async function requestList<T = any>(path: string, options?: RequestInit): Promise<T[]> {
  const data = await request<any>(path, options);
  if (Array.isArray(data)) return data as T[];
  if (data && Array.isArray(data.items)) return data.items as T[];
  if (data && Array.isArray(data.records)) return data.records as T[];
  return [];
}

// ─── Auth / Current User ───────────────────────────────────────────────────────

export interface CurrentUserInfo {
  id: string;
  email: string;
  name: string;
  avatarUrl?: string | null;
  role?: string;
  permissions?: string[];
  department?: { id?: string; name?: string } | null;
  /** The user's configured default store, selected on next login (Req 5.2.3). */
  defaultStoreId?: string | null;
  /** The account's Store_Group_Scope — the Store_Group ids whose stores the
   *  account may see. Absent/null means unrestricted (e.g. Super_Administrator);
   *  it scopes the store switcher to in-scope stores (platform-workspace-rbac
   *  Req 13.5). */
  storeGroupScope?: string[] | null;
  /** The account's Platform_Access — the platform families (`amazon` /
   *  `independent_site` / `logistics` / `finance`) whose Nav_Blocks the account
   *  may enter. Absent/null means unrestricted (e.g. the field is not yet
   *  surfaced by the backend), in which case the navigation falls back to
   *  functional-permission gating only; an explicit list (even empty) gates the
   *  blocks. A Super_Administrator enters every block regardless
   *  (platform-workspace-rbac Req 12.2, 12.5, 15.3). */
  platformAccess?: string[] | null;
}

/**
 * Fetches the logged-in user (identity + permission set) from `GET /api/auth/me`.
 * The permission set is the source of truth for permission-driven UX; callers
 * should prefer this over the cached copy in localStorage so a permission change
 * takes effect on the next fetch without requiring re-login (Req 3.1.5).
 */
export async function fetchCurrentUser() {
  return request<CurrentUserInfo>('/auth/me');
}

// ─── Dashboard ────────────────────────────────────────────────────────────────

export async function fetchDashboardSummary(params?: {
  storeId?: string;
  startDate?: string;
  endDate?: string;
  goalId?: string;
}) {
  const query = new URLSearchParams();
  if (params?.storeId) query.set('storeId', params.storeId);
  if (params?.startDate) query.set('startDate', params.startDate);
  if (params?.endDate) query.set('endDate', params.endDate);
  if (params?.goalId) query.set('goalId', params.goalId);
  const qs = query.toString();
  return request<any>(`/dashboard/summary${qs ? `?${qs}` : ''}`);
}

// ─── AI Advertising Dashboard panels (Req 18) ──────────────────────────────────

export interface DashboardPanelParams {
  storeId?: string;
  marketplace?: string;
  currency?: string;
  startDate?: string;
  endDate?: string;
}

function dashboardQuery(params?: DashboardPanelParams, extra?: Record<string, string>): string {
  const query = new URLSearchParams();
  if (params?.storeId) query.set('storeId', params.storeId);
  if (params?.marketplace) query.set('marketplace', params.marketplace);
  if (params?.currency) query.set('currency', params.currency);
  if (params?.startDate) query.set('startDate', params.startDate);
  if (params?.endDate) query.set('endDate', params.endDate);
  if (extra) {
    for (const [k, v] of Object.entries(extra)) {
      if (v) query.set(k, v);
    }
  }
  const qs = query.toString();
  return qs ? `?${qs}` : '';
}

/** Sales Overview panel — total/ad sales, spend, orders, TACoS, ACoS with deltas (Req 18.1). */
export async function fetchSalesOverview(params?: DashboardPanelParams) {
  return request<any>(`/dashboard/sales-overview${dashboardQuery(params)}`);
}

/** Dual-axis sales trend — ad spend vs ad sales at day/week/month granularity (Req 18.2). */
export async function fetchSalesTrend(params?: DashboardPanelParams & { granularity?: string }) {
  const { granularity, ...rest } = params ?? {};
  return request<any>(`/dashboard/sales-trend${dashboardQuery(rest, { granularity: granularity ?? '' })}`);
}

/** AI Actions panel — per-action counts and measured impact (Req 18.3). */
export async function fetchAiActions(params?: DashboardPanelParams) {
  return request<any>(`/dashboard/ai-actions${dashboardQuery(params)}`);
}

/** AI Usage panel — AI coverage %, AI ad spend, AI ad sales (Req 18.4). */
export async function fetchAiUsage(params?: DashboardPanelParams) {
  return request<any>(`/dashboard/ai-usage${dashboardQuery(params)}`);
}

/** AI Notifications summary — four categories with pending counts (Req 18.5). */
export async function fetchAiNotificationsSummary(params?: DashboardPanelParams) {
  return request<any>(`/dashboard/ai-notifications-summary${dashboardQuery(params)}`);
}

// ─── Goals ────────────────────────────────────────────────────────────────────

export async function fetchGoals(storeId?: string) {
  const qs = storeId ? `?storeId=${storeId}` : '';
  return requestList(`/goals${qs}`);
}

export async function fetchGoalById(id: string) {
  return request<any>(`/goals/${id}`);
}

export async function createGoal(data: any) {
  return request<any>('/goals', { method: 'POST', body: JSON.stringify(data) });
}

export async function updateGoal(id: string, data: any) {
  return request<any>(`/goals/${id}`, { method: 'PUT', body: JSON.stringify(data) });
}

export async function deleteGoal(id: string) {
  return request<any>(`/goals/${id}`, { method: 'DELETE' });
}

// ─── Campaigns ────────────────────────────────────────────────────────────────

/**
 * Campaign row as returned by the backend {@code CampaignVo} (flat performance
 * metrics, hosting state, and the All Search Ads workspace fields). The legacy
 * {@link Campaign} type in {@code types/index.ts} models a different (nested)
 * shape, so the workspace consumes this VO directly.
 */
export interface CampaignVo {
  id: string;
  goalId?: string;
  storeId?: string;
  name: string;
  campaignType?: string;
  portfolio?: string;
  status: string;
  budget: number;
  budgetType?: string;
  startDate?: string;
  endDate?: string;
  targetingType?: string;
  state?: string;
  spend: number;
  sales: number;
  orders: number;
  impressions: number;
  clicks: number;
  acos: number;
  roas: number;
  conversionRate: number;
  avgCpc: number;
  adGroupCount: number;
  keywordCount: number;
  negativeKeywordCount: number;
  externalId?: string;
  hostingEnabled: boolean;
  hostingGoal?: string | null;
  /** AI_Hosting_Status machine value (`hosted` / `not_hosted`); FE translates display copy (Req 14.7, 48.4). */
  aiHostingStatus?: string | null;
  /** Optimization_Goal machine value (`profit_first`/`sales_growth`/`rank`/`clearance`) or null (Req 14.7, 48.4). */
  optimizationGoal?: string | null;
  /** Campaign-level AI_Personality override machine value, or null when no override is set (Req 49.2). */
  campaignPersonality?: string | null;
  targetAcos?: number | null;
  aiManaged: boolean;
  portfolioId?: string | null;
  parentAsin?: string | null;
  targetingGoal?: string | null;
  tags?: string[];
  createdAt?: string;
  updatedAt?: string;
}

export interface PaginatedCampaignResponse {
  items: CampaignVo[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

/**
 * One bucket of the campaign data-trend panel (Req 19.2), aggregated from
 * {@code performance_daily} by the backend {@code GET /api/campaigns/trend}.
 */
export interface CampaignTrendPoint {
  period: string;
  spend: number;
  sales: number;
  orders: number;
  impressions: number;
  clicks: number;
  acos: number;
  cpc: number;
  costPerOrder: number;
}

export interface CampaignBulkResult {
  id: string;
  success: boolean;
  message: string;
}

export interface CampaignCreateInput {
  storeId: string;
  name: string;
  campaignType?: string;
  goalId?: string;
  portfolioId?: string;
  portfolio?: string;
  status?: string;
  budget?: number | null;
  budgetType?: string;
  startDate?: string;
  endDate?: string;
  targetingType?: string;
  tags?: string[];
}

/**
 * List campaigns with the All Search Ads workspace filters (Req 19.4). All
 * filters are optional, combined with AND, and scoped to the active store on
 * the backend.
 */
export async function fetchCampaigns(params?: {
  storeId?: string;
  goalId?: string;
  status?: string;
  adType?: string;
  portfolioId?: string;
  parentAsin?: string;
  targetingGoal?: string;
  targetAcosMin?: number;
  targetAcosMax?: number;
  smartFilter?: string;
  page?: number;
  pageSize?: number;
  sortField?: string;
  sortDir?: 'asc' | 'desc';
}): Promise<PaginatedCampaignResponse> {
  const query = new URLSearchParams();
  if (params?.storeId) query.set('storeId', params.storeId);
  if (params?.goalId) query.set('goalId', params.goalId);
  if (params?.status) query.set('status', params.status);
  if (params?.adType) query.set('adType', params.adType);
  if (params?.portfolioId) query.set('portfolioId', params.portfolioId);
  if (params?.parentAsin) query.set('parentAsin', params.parentAsin);
  if (params?.targetingGoal) query.set('targetingGoal', params.targetingGoal);
  if (params?.targetAcosMin != null) query.set('targetAcosMin', String(params.targetAcosMin));
  if (params?.targetAcosMax != null) query.set('targetAcosMax', String(params.targetAcosMax));
  if (params?.smartFilter) query.set('smartFilter', params.smartFilter);
  if (params?.page) query.set('page', String(params.page));
  if (params?.pageSize) query.set('pageSize', String(params.pageSize));
  // Server-side sort: the backend orders the FULL result set and returns only
  // the requested page in that order (Req 33.1, 33.4).
  if (params?.sortField) query.set('sortField', params.sortField);
  if (params?.sortDir) query.set('sortDir', params.sortDir);
  const qs = query.toString();
  return request<PaginatedCampaignResponse>(`/campaigns${qs ? `?${qs}` : ''}`);
}

export async function fetchCampaignById(id: string) {
  return request<CampaignVo>(`/campaigns/${id}`);
}

export async function updateCampaign(id: string, data: any) {
  return request<any>(`/campaigns/${id}`, { method: 'PATCH', body: JSON.stringify(data) });
}

/** Create a campaign (Req 19.6); returns the created record. */
export async function createCampaign(data: CampaignCreateInput) {
  return request<CampaignVo>('/campaigns', { method: 'POST', body: JSON.stringify(data) });
}

/**
 * Toggle a campaign's enable/pause state via the JSON-body endpoint (Req 19.5).
 * Accepts {@code enable}/{@code pause}; returns the updated campaign.
 */
export async function setCampaignState(id: string, state: string) {
  return request<CampaignVo>(`/campaigns/${id}/state`, {
    method: 'PATCH',
    body: JSON.stringify({ state }),
  });
}

/**
 * Apply one operation (enable / pause / delete) to many campaigns (Req 19.7).
 * Returns a per-item result so partial failures are visible.
 */
export async function bulkCampaignOp(ids: string[], operation: string) {
  return request<CampaignBulkResult[]>('/campaigns/bulk', {
    method: 'POST',
    body: JSON.stringify({ ids, operation }),
  });
}

/**
 * JSON body for {@code PUT /api/campaigns/{id}/hosting} (Req 21.1). {@code targetAcos}
 * is required and must be positive (Req 21.6); {@code hostingGoal} is optional and
 * defaults to {@code maximize_sales_at_target} on the backend when omitted.
 */
export interface CampaignHostingInput {
  targetAcos: number;
  hostingGoal?: string;
}

/**
 * Place a campaign under AI_Hosting by assigning a Hosting_Goal and a Target_ACoS
 * (Req 21.1). The backend validates that {@code targetAcos} is present and positive
 * (Req 21.6); returns the updated campaign.
 */
export async function assignCampaignHosting(id: string, data: CampaignHostingInput) {
  return request<CampaignVo>(`/campaigns/${id}/hosting`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
}

/**
 * Remove a campaign from AI_Hosting (Req 21.5). Persists the un-hosted state so the
 * optimizer stops applying automatic adjustments; returns the updated campaign.
 */
export async function removeCampaignHosting(id: string) {
  return request<CampaignVo>(`/campaigns/${id}/hosting`, { method: 'DELETE' });
}

/** Fetch the campaign data-trend series (Req 19.2) from {@code performance_daily}. */
export async function fetchCampaignTrend(params?: {
  storeId?: string;
  startDate?: string;
  endDate?: string;
  granularity?: string;
}) {
  const query = new URLSearchParams();
  if (params?.storeId) query.set('storeId', params.storeId);
  if (params?.startDate) query.set('startDate', params.startDate);
  if (params?.endDate) query.set('endDate', params.endDate);
  if (params?.granularity) query.set('granularity', params.granularity);
  const qs = query.toString();
  return requestList<CampaignTrendPoint>(`/campaigns/trend${qs ? `?${qs}` : ''}`);
}

// ─── Ad Portfolios ──────────────────────────────────────────────────────────────

export interface AdPortfolio {
  id: string;
  storeId: string;
  name: string;
  state: string;
  budgetType: string;
  budget?: number | null;
  startDate?: string | null;
  endDate?: string | null;
  externalId?: string | null;
  campaignCount: number;
  impressions: number;
  clicks: number;
  ctr: number;
  spend: number;
  cpc: number;
  orders: number;
  sales: number;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface AdPortfolioCreateInput {
  storeId: string;
  name: string;
  state?: string;
  budgetType?: string;
  budget?: number | null;
  startDate?: string;
  endDate?: string;
  externalId?: string;
}

export async function fetchAdPortfolios(storeId?: string) {
  const qs = storeId ? `?storeId=${storeId}` : '';
  return requestList<AdPortfolio>(`/ad-portfolios${qs}`);
}

export async function createAdPortfolio(data: AdPortfolioCreateInput) {
  return request<AdPortfolio>('/ad-portfolios', { method: 'POST', body: JSON.stringify(data) });
}

export async function updateAdPortfolio(id: string, data: Partial<AdPortfolioCreateInput>) {
  return request<AdPortfolio>(`/ad-portfolios/${id}`, { method: 'PUT', body: JSON.stringify(data) });
}

// ─── Smart Diagnosis (Req 22) ──────────────────────────────────────────────────

/** One diagnosed issue derived from the product's ad-structure analysis (Req 22.2). */
export interface DiagnosisIssue {
  type: string;
  priority: string;
  title: string;
  description?: string | null;
}

/** Structured diagnosis result over the product's ad structure (Req 22.2). */
export interface DiagnosisResult {
  /** Whether enough ad-structure data existed to diagnose; false ⇒ insufficient-data result (item 20). */
  dataAvailable?: boolean | null;
  /** Human-readable diagnosis summary (诊断结论). */
  summary?: string | null;
  campaignCount: number;
  enabledCampaignCount: number;
  totalSpend: number;
  totalSales: number;
  acos: number;
  issueCount: number;
  highCount: number;
  mediumCount: number;
  lowCount: number;
  healthScore: number;
  issues: DiagnosisIssue[];
}

/** A Smart Diagnosis task: product (parent ASIN), frequency, creator, last run, result (Req 22.3). */
export interface SmartDiagnosisTask {
  id: string;
  storeId: string;
  parentAsin: string;
  updateFrequency: string;
  status: string;
  createdBy?: string | null;
  lastDiagnosedAt?: string | null;
  createdAt?: string | null;
  result?: DiagnosisResult | null;
}

export interface SmartDiagnosisCreateInput {
  storeId: string;
  parentAsin: string;
  updateFrequency?: string;
}

export async function fetchSmartDiagnosisTasks(storeId?: string) {
  const qs = storeId ? `?storeId=${storeId}` : '';
  return requestList<SmartDiagnosisTask>(`/smart-diagnosis/tasks${qs}`);
}

export async function fetchSmartDiagnosisTask(id: string) {
  return request<SmartDiagnosisTask>(`/smart-diagnosis/tasks/${id}`);
}

export async function createSmartDiagnosisTask(data: SmartDiagnosisCreateInput) {
  return request<SmartDiagnosisTask>('/smart-diagnosis/tasks', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

// ─── Rank Monitoring (Req 28) ──────────────────────────────────────────────────

/** A monitored keyword with its latest organic + ad rank (Req 28.2). */
export interface RankMonitorTask {
  id: string;
  storeId: string;
  /** Store display name (店铺名称) so tasks can be grouped/managed per store. */
  storeName?: string | null;
  productId?: string | null;
  /** Product display name (商品名称); null when the task is not tied to a product. */
  productName?: string | null;
  keywordText: string;
  status: string;
  /** Latest organic rank (自然排名); null until first captured. */
  organicRank?: number | null;
  /** Latest ad rank (广告排名); null until first captured. */
  adRank?: number | null;
  lastCapturedAt?: string | null;
  createdAt?: string | null;
}

/** Consumed/total monitoring quota for the active store (Req 28.3, 28.4). */
export interface RankMonitorQuota {
  consumed: number;
  total: number;
  remaining: number;
  exhausted: boolean;
}

export interface RankMonitorCreateInput {
  storeId: string;
  productId?: string | null;
  keywordText: string;
}

export async function fetchRankMonitorTasks(storeId?: string) {
  const qs = storeId ? `?storeId=${storeId}` : '';
  return requestList<RankMonitorTask>(`/rank-monitor/tasks${qs}`);
}

export async function fetchRankMonitorQuota(storeId?: string) {
  const qs = storeId ? `?storeId=${storeId}` : '';
  return request<RankMonitorQuota>(`/rank-monitor/quota${qs}`);
}

export async function createRankMonitorTask(data: RankMonitorCreateInput) {
  return request<RankMonitorTask>('/rank-monitor/tasks', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

// ─── Creative Asset Library (Req 29) ───────────────────────────────────────────

/** A reusable creative asset (image/video) in the library (Req 29.1). */
export interface CreativeAsset {
  id: string;
  storeId: string;
  name: string;
  /** Creative category: lifestyle|scene|hd_group|marketing. */
  assetType: string;
  /** Media kind: image|video. */
  mediaKind: string;
  /** Stored location (preview/download URL). */
  storageUrl: string;
  asin?: string | null;
  tags?: string[] | null;
  /** Resolved creator display name (创建人). */
  creator?: string | null;
  createdAt?: string | null;
}

/** Fields accompanying a creative-asset upload (Req 29.2). */
export interface CreativeAssetUploadInput {
  file: File;
  storeId: string;
  name: string;
  assetType?: string;
  mediaKind?: string;
  asin?: string;
  /** Comma-joined or array of tags. */
  tags?: string[];
}

export async function fetchCreativeAssets(storeId?: string, opts?: { assetType?: string; search?: string }) {
  const params = new URLSearchParams();
  if (storeId) params.set('storeId', storeId);
  if (opts?.assetType) params.set('assetType', opts.assetType);
  if (opts?.search) params.set('search', opts.search);
  const qs = params.toString();
  return requestList<CreativeAsset>(`/creative-assets${qs ? `?${qs}` : ''}`);
}

export async function uploadCreativeAsset(data: CreativeAssetUploadInput) {
  const form = new FormData();
  form.append('file', data.file);
  form.append('storeId', data.storeId);
  form.append('name', data.name);
  if (data.assetType) form.append('assetType', data.assetType);
  if (data.mediaKind) form.append('mediaKind', data.mediaKind);
  if (data.asin) form.append('asin', data.asin);
  if (data.tags && data.tags.length > 0) form.append('tags', data.tags.join(','));

  // Multipart upload: let the browser set the multipart Content-Type boundary;
  // authFetch attaches the Bearer token. Share request()'s error handling so a
  // non-JSON body never surfaces a raw parse error (Req 1.4) and every non-2xx
  // status surfaces an error indication rather than resolving silently (Req 17.4).
  const res = await authFetch('/api/creative-assets', {
    method: 'POST',
    body: form,
    headers: {},
  });
  return unwrapResponse<CreativeAsset>(res);
}

// ─── AI Notifications (Req 23) ─────────────────────────────────────────────────

/** One AI Notification work item in a category's pending(待处理) / closed(已结束) list. */
export interface AiNotification {
  id: string;
  storeId: string;
  /** core_ops | one_click_optimize | high_potential | target_correction. */
  category: string;
  title: string;
  /** Structured payload (proposed change, affected object, metrics); may be null. */
  detail?: any;
  subjectId?: string | null;
  /** pending | closed. */
  state: string;
  /** applied | confirmed | rejected | dismissed; null while pending. */
  resolution?: string | null;
  createdAt?: string | null;
  closedAt?: string | null;
}

/** One of the four AI_Notification categories with its counts and item lists (Req 23.1, 23.2). */
export interface AiNotificationCategory {
  key: string;
  label: string;
  pendingCount: number;
  closedCount: number;
  pending: AiNotification[];
  closed: AiNotification[];
}

export interface AiNotificationOverview {
  categories: AiNotificationCategory[];
}

/** Per-store configuration controlling which core-ops items are raised (Req 23.5). */
export interface AiNotificationConfig {
  storeId?: string | null;
  config?: any;
  updatedAt?: string | null;
}

export async function fetchAiNotifications(storeId?: string) {
  const qs = storeId ? `?storeId=${storeId}` : '';
  return request<AiNotificationOverview>(`/ai-notifications${qs}`);
}

/** Apply a one-click optimization on a pending item, closing it (Req 23.3). */
export async function applyAiNotification(id: string) {
  return request<AiNotification>(`/ai-notifications/${id}/apply`, { method: 'POST' });
}

/** Confirm an AI target correction, applying the change and closing it (Req 23.4). */
export async function confirmAiNotification(id: string) {
  return request<AiNotification>(`/ai-notifications/${id}/confirm`, { method: 'POST' });
}

/** Reject an AI target correction, discarding the change and closing it (Req 23.4). */
export async function rejectAiNotification(id: string) {
  return request<AiNotification>(`/ai-notifications/${id}/reject`, { method: 'POST' });
}

export async function fetchAiNotificationConfig(storeId?: string) {
  const qs = storeId ? `?storeId=${storeId}` : '';
  return request<AiNotificationConfig>(`/ai-notifications/config${qs}`);
}

export async function updateAiNotificationConfig(data: { storeId: string; config: any }) {
  return request<AiNotificationConfig>('/ai-notifications/config', {
    method: 'PUT',
    body: JSON.stringify(data),
  });
}

// ─── Automation Rule Templates (Req 9, 25) ─────────────────────────────────────
/**
 * A reusable condition-to-action automation rule template (Req 25.1). The
 * {@code condition} / {@code action} blobs follow the evaluator grammar:
 * a condition leaf is {@code { metric, op, value }} (op ∈ gt|gte|lt|lte|eq|ne),
 * combinable with {@code and/or/not}; an action is {@code { type, params }}.
 */
export interface RuleTemplate {
  id: string;
  storeId?: string | null;
  name: string;
  templateType: string;
  condition?: any;
  action?: any;
  status: string;
  linkedObjectCount: number;
  createdBy?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface RuleTemplateCreateInput {
  storeId: string;
  name: string;
  templateType: string;
  condition: any;
  action: any;
  status?: string;
}

export interface RuleTemplateBulkResult {
  id: string;
  success: boolean;
  message: string;
}

/** List rule templates for a store (Req 25.1). */
export async function fetchRuleTemplates(storeId?: string) {
  const qs = storeId ? `?storeId=${storeId}` : '';
  return requestList<RuleTemplate>(`/automation/rule-templates${qs}`);
}

/** Create a rule template (Req 9.1, 25.2); returns the created record. */
export async function createRuleTemplate(data: RuleTemplateCreateInput) {
  return request<RuleTemplate>('/automation/rule-templates', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

/** Update a rule template (Req 25.2). */
export async function updateRuleTemplate(id: string, data: Partial<RuleTemplateCreateInput>) {
  return request<RuleTemplate>(`/automation/rule-templates/${id}`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
}

/** Apply one operation (enable / disable / delete) to many templates (Req 25.4). */
export async function bulkRuleTemplateOp(ids: string[], operation: string) {
  return request<RuleTemplateBulkResult[]>('/automation/rule-templates/bulk', {
    method: 'POST',
    body: JSON.stringify({ ids, operation }),
  });
}

// ─── Ad Placement Locks (Req 26) ───────────────────────────────────────────────

/**
 * An Ad Placement Lock strategy (卡位策略) that holds an SP campaign in a targeted
 * Amazon ad placement by clamping linked keyword bids into a configured range.
 */
export interface PlacementLockStrategy {
  id: string;
  storeId: string;
  campaignId: string;
  campaignName?: string | null;
  targetPlacement: string;
  bidMin: number;
  bidMax: number;
  status: string;
  createdAt?: string | null;
}

export interface PlacementLockCreateInput {
  storeId: string;
  campaignId: string;
  targetPlacement: string;
  bidMin: number;
  bidMax: number;
}

/** A per-keyword enforcement task for a placement-lock strategy (Req 26.2). */
export interface PlacementLockTask {
  id: string;
  strategyId: string;
  targetPlacement?: string | null;
  campaignName?: string | null;
  keywordId?: string | null;
  keywordText?: string | null;
  lastBid?: number | null;
  lastRunAt?: string | null;
  createdAt?: string | null;
}

/** AMS real-time data row, stubbed from stored enforcement state (Req 26.1). */
export interface PlacementLockAms {
  strategyId: string;
  campaignName?: string | null;
  targetPlacement: string;
  keywordText?: string | null;
  currentBid?: number | null;
  bidMin?: number | null;
  bidMax?: number | null;
  status?: string | null;
  observedAt?: string | null;
}

/** List placement-lock strategies for a store (Req 26.1). */
export async function fetchPlacementLocks(storeId?: string) {
  const qs = storeId ? `?storeId=${storeId}` : '';
  return requestList<PlacementLockStrategy>(`/placement-locks${qs}`);
}

/** Create a placement-lock strategy (Req 26.2); the backend rejects min > max (Req 26.4). */
export async function createPlacementLock(data: PlacementLockCreateInput) {
  return request<PlacementLockStrategy>('/placement-locks', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

/** List per-keyword enforcement tasks for a store's strategies (Req 26.2). */
export async function fetchPlacementLockTasks(storeId?: string) {
  const qs = storeId ? `?storeId=${storeId}` : '';
  return requestList<PlacementLockTask>(`/placement-locks/tasks${qs}`);
}

/** List AMS real-time data, stubbed from stored enforcement state (Req 26.1). */
export async function fetchPlacementLockAms(storeId?: string) {
  const qs = storeId ? `?storeId=${storeId}` : '';
  return requestList<PlacementLockAms>(`/placement-locks/ams${qs}`);
}

/** Link campaigns / targets / keywords to a template (Req 25.3). */
export async function linkRuleTemplateObjects(
  id: string,
  links: { objectType: string; objectId: string }[],
) {
  return request<RuleTemplate>(`/automation/rule-templates/${id}/links`, {
    method: 'POST',
    body: JSON.stringify({ links }),
  });
}

// ─── Keywords ─────────────────────────────────────────────────────────────────

export async function fetchKeywords(params?: {
  campaignId?: string;
  adGroupId?: string;
  matchType?: string;
  status?: string;
}) {
  const query = new URLSearchParams();
  if (params?.campaignId) query.set('campaignId', params.campaignId);
  if (params?.adGroupId) query.set('adGroupId', params.adGroupId);
  if (params?.matchType) query.set('matchType', params.matchType);
  if (params?.status) query.set('status', params.status);
  const qs = query.toString();
  return requestList(`/keywords${qs ? `?${qs}` : ''}`);
}

export async function updateKeyword(id: string, data: { bid?: number; status?: string }) {
  return request<any>(`/keywords/${id}`, { method: 'PATCH', body: JSON.stringify(data) });
}

// ─── Search Terms ─────────────────────────────────────────────────────────────

export async function fetchSearchTerms(params?: {
  campaignId?: string;
  harvestingStatus?: string;
  startDate?: string;
  endDate?: string;
}) {
  const query = new URLSearchParams();
  if (params?.campaignId) query.set('campaignId', params.campaignId);
  if (params?.harvestingStatus) query.set('harvestingStatus', params.harvestingStatus);
  if (params?.startDate) query.set('startDate', params.startDate);
  if (params?.endDate) query.set('endDate', params.endDate);
  const qs = query.toString();
  return requestList(`/search-terms${qs ? `?${qs}` : ''}`);
}

export async function harvestSearchTerm(id: string, action: string) {
  return request<any>(`/search-terms/${id}/harvest`, {
    method: 'POST',
    body: JSON.stringify({ action }),
  });
}

// ─── Recommendations ──────────────────────────────────────────────────────────

export async function fetchRecommendations(params?: {
  storeId?: string;
  status?: string;
  riskLevel?: string;
  type?: string;
}) {
  const query = new URLSearchParams();
  if (params?.storeId) query.set('storeId', params.storeId);
  if (params?.status) query.set('status', params.status);
  if (params?.riskLevel) query.set('riskLevel', params.riskLevel);
  if (params?.type) query.set('type', params.type);
  const qs = query.toString();
  return requestList(`/recommendations${qs ? `?${qs}` : ''}`);
}

export async function generateRecommendations(storeId: string) {
  // Backend expects storeId as a query parameter and returns the count of
  // recommendations generated.
  return request<number>(`/recommendations/generate?storeId=${encodeURIComponent(storeId)}`, {
    method: 'POST',
  });
}

export async function applyRecommendation(id: string) {
  return request<any>(`/recommendations/${id}/apply`, { method: 'POST' });
}

export async function dismissRecommendation(id: string) {
  return request<any>(`/recommendations/${id}/dismiss`, { method: 'POST' });
}

export async function watchRecommendation(id: string) {
  return request<any>(`/recommendations/${id}/watch`, { method: 'POST' });
}

// ─── Products ─────────────────────────────────────────────────────────────────

export async function fetchProducts(storeId?: string) {
  const qs = storeId ? `?storeId=${storeId}` : '';
  return requestList(`/products${qs}`);
}

export async function createProduct(data: any) {
  return request<any>('/products', { method: 'POST', body: JSON.stringify(data) });
}

export async function updateProduct(id: string, data: any) {
  return request<any>(`/products/${id}`, { method: 'PUT', body: JSON.stringify(data) });
}

// ─── Reports ──────────────────────────────────────────────────────────────────

export async function fetchReports(storeId?: string) {
  const qs = storeId ? `?storeId=${storeId}` : '';
  return requestList(`/reports${qs}`);
}

export async function generateReport(data: {
  storeId: string;
  type: string;
  periodStart: string;
  periodEnd: string;
}) {
  return request<any>('/reports/generate', { method: 'POST', body: JSON.stringify(data) });
}

// ─── Stores ───────────────────────────────────────────────────────────────────

export async function fetchStores(orgId?: string) {
  const qs = orgId ? `?orgId=${orgId}` : '';
  return requestList(`/stores${qs}`);
}

export async function createStore(payload: { name: string; marketplaceId: string; sellerId?: string; status?: string }) {
  return request<any>('/stores', { method: 'POST', body: JSON.stringify(payload) });
}

export async function updateStore(id: string, payload: { name?: string; marketplaceId?: string; sellerId?: string; status?: string }) {
  return request<any>(`/stores/${id}`, { method: 'PUT', body: JSON.stringify(payload) });
}

export async function deleteStore(id: string) {
  return request<any>(`/stores/${id}`, { method: 'DELETE' });
}

// ─── Store assignment (admin → operator) ───────────────────────────────────
export async function fetchUserStores(userId: string) {
  return requestList<string>(`/users/${userId}/stores`);
}

export async function assignUserStores(userId: string, storeIds: string[]) {
  return request<any>(`/users/${userId}/stores`, { method: 'PUT', body: JSON.stringify({ storeIds }) });
}

// ─── One-click store binding (reuse admin-configured platform credentials) ──
export async function fetchConfiguredPlatforms() {
  return requestList<string>('/platform-connections/configured');
}

export async function bindStorePlatform(storeId: string, platform: string) {
  return request<any>(`/stores/${storeId}/bind`, { method: 'POST', body: JSON.stringify({ platform }) });
}

// ─── Marketplaces ─────────────────────────────────────────────────────────────

export async function fetchMarketplaces() {
  return requestList('/marketplaces');
}

// ─── Audit Logs ───────────────────────────────────────────────────────────────

export async function fetchAuditLogs(params?: { action?: string; entityType?: string }) {
  const query = new URLSearchParams();
  if (params?.action) query.set('action', params.action);
  if (params?.entityType) query.set('entityType', params.entityType);
  const qs = query.toString();
  return requestList(`/audit-logs${qs ? `?${qs}` : ''}`);
}

// ─── Tasks ───────────────────────────────────────────────────────────────────

export async function fetchTasks(params?: {
  storeId?: string;
  status?: string;
  priority?: string;
  taskType?: string;
  page?: number;
  pageSize?: number;
}) {
  const query = new URLSearchParams();
  if (params?.storeId) query.set('storeId', params.storeId);
  if (params?.status) query.set('status', params.status);
  if (params?.priority) query.set('priority', params.priority);
  if (params?.taskType) query.set('taskType', params.taskType);
  if (params?.page) query.set('page', String(params.page));
  if (params?.pageSize) query.set('pageSize', String(params.pageSize));
  const qs = query.toString();
  return request<any>(`/tasks${qs ? `?${qs}` : ''}`);
}

export async function fetchTaskById(id: string) {
  return request<any>(`/tasks/${id}`);
}

export async function createTask(data: {
  storeId: string;
  title: string;
  description?: string;
  taskType: string;
  sourceType?: string;
  priority?: string;
  riskLevel?: string;
}) {
  return request<any>('/tasks', { method: 'POST', body: JSON.stringify(data) });
}

export async function completeTask(id: string) {
  return request<any>(`/tasks/${id}/complete`, { method: 'POST' });
}

export async function dismissTask(id: string) {
  return request<any>(`/tasks/${id}/dismiss`, { method: 'POST' });
}

export async function assignTask(id: string, userId: string) {
  return request<any>(`/tasks/${id}/assign`, {
    method: 'POST',
    body: JSON.stringify({ userId }),
  });
}

// ─── Keyword Intelligence ─────────────────────────────────────────────────────

export async function fetchKeywordIntelligenceOverview(storeId: string) {
  return request<any>(`/keyword-intelligence/overview?storeId=${storeId}`);
}

export async function fetchKeywordInsights(storeId: string, params?: {
  segment?: string;
  status?: string;
  source?: string;
  riskLevel?: string;
  search?: string;
  page?: number;
  pageSize?: number;
}) {
  const query = new URLSearchParams({ storeId });
  if (params?.segment) query.set('segment', params.segment);
  if (params?.status) query.set('status', params.status);
  if (params?.source) query.set('source', params.source);
  if (params?.riskLevel) query.set('riskLevel', params.riskLevel);
  if (params?.search) query.set('search', params.search);
  if (params?.page) query.set('page', String(params.page));
  if (params?.pageSize) query.set('pageSize', String(params.pageSize));
  // Endpoint may return a raw array or a paginated object; normalize to array
  // so the page can iterate/spread safely ("i is not iterable" guard).
  return requestList<any>(`/keyword-intelligence/insights?${query.toString()}`);
}

export async function analyzeKeywordHealth(storeId: string) {
  return request<any>('/keyword-intelligence/analyze', {
    method: 'POST',
    body: JSON.stringify({ storeId }),
  });
}

export async function applyKeywordInsight(id: string) {
  return request<any>(`/keyword-intelligence/${id}/apply`, { method: 'POST' });
}

export async function watchKeywordInsight(id: string) {
  return request<any>(`/keyword-intelligence/${id}/watch`, { method: 'POST' });
}

export async function dismissKeywordInsight(id: string) {
  return request<any>(`/keyword-intelligence/${id}/dismiss`, { method: 'POST' });
}

// ─── Keyword Libraries (词库) + recommendations ─────────────────────────────────

/** A managed collection of keywords (词库) associated with products (Req 27.2). */
export interface KeywordLibrary {
  id: string;
  storeId: string;
  name: string;
  /** Library type (词库类型): harvest | negative | brand | competitor. */
  libraryType: string;
  scheduleCron?: string | null;
  /** Number of distinct associated products (关联商品). */
  associatedProductCount: number;
  /** Number of keywords in the library (关键词数量). */
  keywordCount: number;
  /** Last execution time (上次执行). */
  lastRunAt?: string | null;
  /** Next execution time (下次执行). */
  nextRunAt?: string | null;
  createdAt?: string | null;
}

export interface KeywordLibraryCreateInput {
  storeId: string;
  name: string;
  libraryType: string;
  scheduleCron?: string;
  productIds?: string[];
  keywords?: string[];
}

/** List keyword libraries for a store (Req 27.1, 27.2). */
export async function fetchKeywordLibraries(storeId?: string) {
  const qs = storeId ? `?storeId=${storeId}` : '';
  return requestList<KeywordLibrary>(`/keyword-libraries${qs}`);
}

/** Create a keyword library (创建词库, Req 27.3). */
export async function createKeywordLibrary(data: KeywordLibraryCreateInput) {
  return request<KeywordLibrary>('/keyword-libraries', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

/** Harvest a keyword recommendation as a positive keyword (Req 27.4). */
export async function harvestKeywordRecommendation(id: string) {
  return request<Record<string, unknown>>(`/keyword-libraries/recommendations/${id}/harvest`, {
    method: 'POST',
  });
}

/** Add a keyword recommendation as a negative keyword (Req 27.4). */
export async function negateKeywordRecommendation(id: string) {
  return request<Record<string, unknown>>(`/keyword-libraries/recommendations/${id}/negate`, {
    method: 'POST',
  });
}

export async function fetchKeywordSummary(storeId: string) {
  return request<any>(`/keyword-intelligence/summary?storeId=${storeId}`);
}

// ─── Keyword & Automation Config (关键词与自动化配置) ───────────────────────────
// Store-level seed terms feeding harvesting / negation / automation. Persisted
// by reusing keyword_libraries with a seed_* library type per category.

/** The four seed categories configured per store. */
export interface KeywordConfig {
  /** 品牌关键词 (brand keywords). */
  brand: string[];
  /** 品类关键词 (category keywords). */
  category: string[];
  /** 竞品品牌 (competitor brands). */
  competitorBrand: string[];
  /** 竞品 ASIN (competitor ASINs). */
  competitorAsin: string[];
}

export type KeywordConfigCategory = 'brand' | 'category' | 'competitorBrand' | 'competitorAsin';

/** Get the store-level keyword/automation seed configuration. */
export async function fetchKeywordConfig(storeId: string) {
  return request<KeywordConfig>(`/keyword-libraries/config?storeId=${storeId}`);
}

/** Add seed terms to a keyword-config category; returns the updated config. */
export async function addKeywordConfig(
  storeId: string,
  category: KeywordConfigCategory,
  terms: string[],
) {
  return request<KeywordConfig>('/keyword-libraries/config', {
    method: 'POST',
    body: JSON.stringify({ storeId, category, terms }),
  });
}

// ─── Listing AI ───────────────────────────────────────────────────────────────

export async function fetchListingContent(productId: string) {
  return request<any>(`/listing-ai/products/${productId}/listing-content`);
}

export async function generateListingDraft(productId: string, data: {
  marketplaceId?: string;
  /**
   * Optional platform/channel override (e.g. `amazon`, `shopify`, `woocommerce`,
   * `independent_site`, `tiktok`). When omitted the backend resolves the platform
   * family from the product's store, so callers do not have to pass it.
   */
  platform?: string;
  targetAudience?: string;
  sellingPoints?: string[];
  competitorAsins?: string[];
  tone?: 'professional' | 'casual' | 'technical';
}) {
  return request<any>(`/listing-ai/products/${productId}/generate`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function scoreListing(productId: string) {
  return request<any>(`/listing-ai/products/${productId}/score`, { method: 'POST' });
}

export async function checkListingCompliance(productId: string) {
  return request<any>(`/listing-ai/products/${productId}/compliance-check`, { method: 'POST' });
}

export async function fetchKeywordMapping(productId: string) {
  return request<any>(`/listing-ai/products/${productId}/keyword-mapping`);
}

export async function fetchListingVersions(productId: string) {
  return request<any>(`/listing-ai/products/${productId}/versions`);
}

export async function updateListingDraft(id: string, data: any) {
  return request<any>(`/listing-ai/drafts/${id}`, { method: 'PATCH', body: JSON.stringify(data) });
}

export async function approveListingDraft(id: string) {
  return request<any>(`/listing-ai/drafts/${id}/approve`, { method: 'POST' });
}

// ─── Product Upload ───────────────────────────────────────────────────────────

export async function fetchUploadJobs(storeId?: string, status?: string) {
  const query = new URLSearchParams();
  if (storeId) query.set('storeId', storeId);
  if (status) query.set('status', status);
  const qs = query.toString();
  return requestList(`/product-upload/jobs${qs ? `?${qs}` : ''}`);
}

export async function createUploadJob(data: {
  storeId: string;
  productId: string;
  marketplaceId: string;
  uploadMethod: string;
}) {
  return request<any>('/product-upload/jobs', { method: 'POST', body: JSON.stringify(data) });
}

export async function fetchUploadJobById(id: string) {
  return request<any>(`/product-upload/jobs/${id}`);
}

export async function validateUploadJob(id: string) {
  return request<any>(`/product-upload/jobs/${id}/validate`, { method: 'POST' });
}

export async function approveUploadJob(id: string) {
  return request<any>(`/product-upload/jobs/${id}/approve`, { method: 'POST' });
}

export async function exportUploadJob(id: string, format: 'json' | 'csv' | 'flat_file') {
  return request<any>(`/product-upload/jobs/${id}/export?format=${encodeURIComponent(format)}`, {
    method: 'POST',
  });
}

export async function cancelUploadJob(id: string) {
  return request<any>(`/product-upload/jobs/${id}/cancel`, { method: 'POST' });
}

/**
 * Directly publish an approved upload job to the connected sales channel
 * (Shopify / WooCommerce). The backend makes a real product-create call and
 * returns the job with publish_mode=direct + platform_product_id on success,
 * or status=failed with a readable reason on rejection. Never fakes success.
 */
export async function publishUploadJob(id: string) {
  return request<any>(`/product-upload/jobs/${id}/publish`, { method: 'POST' });
}

// ─── Imports (Phase 4A) ───────────────────────────────────────────────────────

export async function fetchImports(storeId?: string) {
  const qs = storeId ? `?storeId=${storeId}` : '';
  return requestList(`/imports${qs}`);
}

export async function uploadImportFile(formData: FormData) {
  // Multipart upload: let the browser set Content-Type; authFetch adds the token.
  // Share request()'s error handling so a non-2xx status or non-JSON body
  // surfaces an error indication rather than resolving silently (Req 17.4, 1.4).
  const res = await authFetch('/api/imports/upload', {
    method: 'POST',
    body: formData,
    headers: {},
  });
  return unwrapResponse<any>(res);
}

export async function fetchImportById(id: string) {
  return request<any>(`/imports/${id}`);
}

export async function previewImport(id: string) {
  return request<any>(`/imports/${id}/preview`, { method: 'POST' });
}

export async function mapImport(id: string, mapping: Record<string, string>) {
  return request<any>(`/imports/${id}/map`, { method: 'POST', body: JSON.stringify({ mapping }) });
}

export async function validateImport(id: string) {
  return request<any>(`/imports/${id}/validate`, { method: 'POST' });
}

export async function commitImport(id: string) {
  return request<any>(`/imports/${id}/commit`, { method: 'POST' });
}

export async function fetchImportErrors(id: string) {
  return request<any>(`/imports/${id}/errors`);
}

export async function reanalyzeImport(id: string) {
  return request<any>(`/imports/${id}/reanalyze`, { method: 'POST' });
}

// ─── Data Quality ────────────────────────────────────────────────────────────

export async function fetchDataQualityIssues(params?: { storeId?: string; status?: string; severity?: string }) {
  const query = new URLSearchParams();
  if (params?.storeId) query.set('storeId', params.storeId);
  if (params?.status) query.set('status', params.status);
  if (params?.severity) query.set('severity', params.severity);
  const qs = query.toString();
  return requestList(`/data-quality/issues${qs ? `?${qs}` : ''}`);
}

export async function runDataQualityCheck(storeId: string) {
  const qs = storeId ? `?storeId=${encodeURIComponent(storeId)}` : '';
  return request<any>(`/data-quality/check${qs}`, {
    method: 'POST',
  });
}

export async function resolveDataQualityIssue(id: string) {
  return request<any>(`/data-quality/issues/${id}/resolve`, { method: 'POST' });
}

export async function ignoreDataQualityIssue(id: string) {
  return request<any>(`/data-quality/issues/${id}/ignore`, { method: 'POST' });
}

// ─── Profit Dashboard ───────────────────────────────────────────────────────

export async function fetchProfitDashboard(params: { storeId: string; startDate?: string; endDate?: string }) {
  const query = new URLSearchParams({ storeId: params.storeId });
  if (params.startDate) query.set('startDate', params.startDate);
  if (params.endDate) query.set('endDate', params.endDate);
  const qs = query.toString();
  return request<any>(`/profit/dashboard${qs ? `?${qs}` : ''}`);
}

// ─── Product Profit ─────────────────────────────────────────────────────────

export async function fetchProductProfit(params: {
  storeId: string;
  startDate?: string;
  endDate?: string;
  marketplace?: string;
  search?: string;
  page?: number;
  pageSize?: number;
}) {
  const query = new URLSearchParams({ storeId: params.storeId });
  if (params.startDate) query.set('startDate', params.startDate);
  if (params.endDate) query.set('endDate', params.endDate);
  if (params.marketplace) query.set('marketplace', params.marketplace);
  if (params.search) query.set('search', params.search);
  if (params.page) query.set('page', String(params.page));
  if (params.pageSize) query.set('pageSize', String(params.pageSize));
  const qs = query.toString();
  return request<any>(`/profit/products${qs ? `?${qs}` : ''}`);
}

// ─── Inventory Health ───────────────────────────────────────────────────────

export function normalizeInventoryItem(item: any) {
  return {
    ...item,
    name: item?.name ?? item?.productName ?? '',
    value: Number(item?.value ?? item?.inventoryValue ?? 0),
    reason: item?.reason ?? item?.recommendation ?? '',
  };
}

export function normalizeInventoryHealth(raw: any) {
  const normalizeList = (value: any) => Array.isArray(value) ? value.map(normalizeInventoryItem) : [];
  return {
    ...raw,
    totalValue: Number(raw?.totalValue ?? raw?.totalInventoryValue ?? 0),
    lowStockCount: Number(raw?.lowStockCount ?? 0),
    overstockCount: Number(raw?.overstockCount ?? 0),
    avgDaysOfSupply: Number(raw?.avgDaysOfSupply ?? 0),
    stockoutRisks: normalizeList(raw?.stockoutRisks),
    overstockRisks: normalizeList(raw?.overstockRisks),
    notSafeToScale: normalizeList(raw?.notSafeToScale),
    clearanceCandidates: normalizeList(raw?.clearanceCandidates),
  };
}

export async function fetchInventoryHealth(params?: { storeId?: string }) {
  const qs = params?.storeId ? `?storeId=${encodeURIComponent(params.storeId)}` : '';
  return normalizeInventoryHealth(await request<any>(`/inventory/health${qs}`));
}

// ─── Replenishment ──────────────────────────────────────────────────────────

export function normalizeReplenishmentPlan(plan: any) {
  return {
    ...plan,
    name: plan?.name ?? plan?.productName ?? '',
    recommendedQty: Number(plan?.recommendedQty ?? 0),
    purchaseCost: Number(plan?.purchaseCost ?? 0),
    shippingCost: Number(plan?.shippingCost ?? 0),
  };
}

export async function fetchReplenishmentPlans(params?: { storeId?: string; status?: string }) {
  const query = new URLSearchParams();
  if (params?.storeId) query.set('storeId', params.storeId);
  if (params?.status) query.set('status', params.status);
  const qs = query.toString();
  const result = await request<any>(`/replenishment/plans${qs ? `?${qs}` : ''}`);
  const plans = result?.plans ?? result?.items ?? result;
  return Array.isArray(plans) ? plans.map(normalizeReplenishmentPlan) : [];
}

export async function generateReplenishmentPlans(storeId: string) {
  const result = await request<any>('/replenishment/generate', {
    method: 'POST',
    body: JSON.stringify({ storeId }),
  });
  return Array.isArray(result) ? result.map(normalizeReplenishmentPlan) : [];
}

export async function approveReplenishmentPlan(id: string) {
  return normalizeReplenishmentPlan(
    await request<any>(`/replenishment/plans/${id}/approve`, { method: 'POST' }),
  );
}

export async function cancelReplenishmentPlan(id: string) {
  return normalizeReplenishmentPlan(
    await request<any>(`/replenishment/plans/${id}/cancel`, { method: 'POST' }),
  );
}

export async function updateReplenishmentPlan(id: string, data: any) {
  return request<any>(`/replenishment/plans/${id}`, { method: 'PATCH', body: JSON.stringify(data) });
}

// ─── Customer Tickets ──────────────────────────────────────────────────────

export async function fetchCustomerTickets(params?: {
  storeId?: string;
  status?: string;
  priority?: string;
  category?: string;
  search?: string;
  page?: number;
  pageSize?: number;
}) {
  const query = new URLSearchParams();
  if (params?.storeId) query.set('storeId', params.storeId);
  if (params?.status) query.set('status', params.status);
  if (params?.priority) query.set('priority', params.priority);
  if (params?.category) query.set('category', params.category);
  if (params?.search) query.set('search', params.search);
  if (params?.page) query.set('page', String(params.page));
  if (params?.pageSize) query.set('pageSize', String(params.pageSize));
  const qs = query.toString();
  return request<any>(`/customer-tickets${qs ? `?${qs}` : ''}`);
}

/**
 * An AI assistance result for a Customer_Ticket produced by the backend
 * Ticket_AI_Assistant (platform-workspace-rbac Req 9.3). Every field is a
 * PROPOSAL: the draft reply, the suggested classification, and the suggested
 * handling action are surfaced for human review and are NOT sent or applied
 * until the operator explicitly confirms them (Req 9.4).
 */
export interface TicketAiProposal {
  ticketId?: string | null;
  /** Proposed reply text the operator may edit before confirming a send. */
  draft: string;
  /** Suggested ticket classification/category, or null when not produced. */
  classification?: string | null;
  /** Suggested handling action (e.g. a status change), or null. */
  suggestedAction?: string | null;
  /** `ai` when produced by the model, `template` for the deterministic fallback. */
  generatedBy?: string | null;
}

/**
 * Request AI assistance for a Customer_Ticket (Req 9.3). Prefers the
 * Ticket_AI_Assistant `assist` endpoint (draft + classification + action); if
 * that endpoint is not yet available it falls back to the reply-draft endpoint
 * so a draft proposal is still produced. The result is always treated as a
 * proposal requiring explicit confirmation before anything is sent/applied
 * (Req 9.4); a generation failure rejects so the caller can surface the reason
 * and leave the ticket unchanged (Req 9.6).
 */
export async function assistCustomerTicket(id: string): Promise<TicketAiProposal> {
  // Backend TicketAiProposalVo shape (CustomerController POST /ai-assist): the
  // assistant draft is `draftReply` and the suggested category is
  // `suggestedClassification`. Map those to this client's proposal shape.
  interface TicketAiProposalVo {
    ticketId?: string | null;
    draftReply?: string | null;
    suggestedClassification?: string | null;
    suggestedAction?: string | null;
    generatedBy?: string | null;
  }
  try {
    const vo = await request<TicketAiProposalVo>(`/customer-tickets/${id}/ai-assist`, { method: 'POST' });
    return {
      ticketId: vo.ticketId ?? id,
      draft: vo.draftReply ?? '',
      classification: vo.suggestedClassification ?? null,
      suggestedAction: vo.suggestedAction ?? null,
      generatedBy: vo.generatedBy ?? null,
    };
  } catch {
    // Fallback: the full assist contract is not deployed yet — derive a
    // draft-only proposal from the reply-draft endpoint.
    const draftResult = await request<{ ticketId?: string; draft: string; generatedBy?: string }>(
      `/customer-tickets/${id}/reply-draft`,
      { method: 'POST' },
    );
    return {
      ticketId: draftResult.ticketId ?? id,
      draft: draftResult.draft,
      classification: null,
      suggestedAction: null,
      generatedBy: draftResult.generatedBy ?? null,
    };
  }
}

/**
 * The operator-confirmed subset of an AI proposal to apply (Req 9.5). Only the
 * fields the operator explicitly confirmed are sent; the backend applies the
 * confirmed reply/classification/action and records AI provenance in the audit
 * trail.
 */
export interface TicketProposalConfirmInput {
  /** Confirmed (possibly edited) reply text to send to the buyer. */
  reply?: string;
  /** Confirmed classification to apply to the ticket. */
  classification?: string;
  /** Confirmed handling action to apply (e.g. a status transition). */
  action?: string;
}

/**
 * Apply an operator-confirmed AI proposal to a Customer_Ticket (Req 9.5).
 * Nothing is sent or applied by {@link assistCustomerTicket}; only this explicit
 * confirmation mutates the ticket or sends the reply.
 */
export async function confirmCustomerTicketProposal(id: string, data: TicketProposalConfirmInput) {
  // Backend TicketAiConfirmCommand (CustomerController POST /ai-confirm) accepts
  // { reply, classification, action }; only the fields the operator confirmed
  // are sent (Req 9.4, 9.5).
  return request<any>(`/customer-tickets/${id}/ai-confirm`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

// ─── Feedback ──────────────────────────────────────────────────────────────

export async function fetchFeedback(params?: {
  type?: string;
  status?: string;
  asin?: string;
  search?: string;
  storeId?: string;
  page?: number;
  pageSize?: number;
}) {
  const query = new URLSearchParams();
  if (params?.type) query.set('type', params.type);
  if (params?.status) query.set('status', params.status);
  if (params?.asin) query.set('asin', params.asin);
  if (params?.search) query.set('search', params.search);
  if (params?.storeId) query.set('storeId', params.storeId);
  if (params?.page) query.set('page', String(params.page));
  if (params?.pageSize) query.set('pageSize', String(params.pageSize));
  const qs = query.toString();
  return request<any>(`/feedback${qs ? `?${qs}` : ''}`);
}

// ─── Platform Connections ──────────────────────────────────────────────────

export async function fetchPlatformConnections() {
  // Request a large page so every saved connection is listed (the backend
  // paginates with a default pageSize of 20). requestList normalizes the
  // PageResponse { items } envelope to a plain array.
  return requestList('/platform-connections?page=1&pageSize=500');
}

/**
 * Create a new platform connection. The backend allows many connections to
 * target the same platform, each created and listed independently (Req 13.5).
 */
export async function createPlatformConnection(payload: {
  platform: string;
  connectionName?: string;
  storeId?: string;
  config: Record<string, string>;
}) {
  return request<any>('/platform-connections', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

/** One-step "connect a store": creates the store + its platform connection in
 *  a single call (no separate store creation needed). */
export async function connectStore(payload: {
  platform: string;
  storeName?: string;
  config: Record<string, string>;
}) {
  return request<any>('/stores/connect', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

/**
 * One-step independent-site store connection (platform-workspace-rbac Req 5.2).
 * Validates the Shopify / WooCommerce / TikTok credentials, then creates the
 * store + its platform connection AND assigns the store to the independent-site
 * Store_Group system — unlike the generic `/stores/connect`, which does not do
 * the store-group assignment. Used by the 独立站店铺连接 entry.
 */
export async function connectIndependentSiteStore(payload: {
  platform: string;
  storeName?: string;
  config: Record<string, string>;
  storeGroupId?: string;
}) {
  return request<any>('/independent-site/connect/store', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

/** Update an existing platform connection by its id. */
export async function updatePlatformConnection(id: string, payload: {
  platform?: string;
  connectionName?: string;
  storeId?: string;
  config?: Record<string, string>;
}) {
  return request<any>(`/platform-connections/${id}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

export async function connectPlatform(platformId: string) {
  return request<any>(`/platform-connections/${platformId}/connect`, { method: 'POST' });
}

export async function disconnectPlatform(platformId: string) {
  return request<any>(`/platform-connections/${platformId}/disconnect`, { method: 'POST' });
}

export async function testPlatformConnection(platformId: string) {
  return request<any>(`/platform-connections/${platformId}/test`, { method: 'POST' });
}

export async function fetchPlatformFields(platformId: string) {
  return requestList<any>(`/platform-connections/${platformId}/fields`);
}

export async function fetchPlatformConfig(platformId: string) {
  return request<any>(`/platform-connections/${platformId}/config`);
}

export async function savePlatformConfig(platformId: string, payload: {
  storeId?: string;
  connectionName?: string;
  config: Record<string, string>;
}) {
  return request<any>(`/platform-connections/${platformId}/config`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

// ─── Amazon Ads OAuth (Login with Amazon) store-connection wizard ───────────
// Real configurable ad-account connection flow. Credentials (client-id /
// client-secret / redirect-uri) are configured server-side in
// adpilot.amazon-ads.*; when unset the backend returns a clear 4xx, never a 500.
//
// NOTE: this authorizes the ADVERTISING account only. Product upload via the
// Amazon Selling Partner API (SP-API) is a separate, future authorization and
// is intentionally not implemented here.

export interface AmazonAdsAuthUrl {
  authorizeUrl: string;
  state: string;
  region: string;
  storeId: string;
}

export interface AmazonAdsProfile {
  profileId: string;
  countryCode?: string;
  currencyCode?: string;
  marketplaceId?: string;
  accountName?: string;
  sellerStringId?: string;
  accountType?: string;
}

export interface AmazonAdsCallbackResult {
  storeId: string;
  region: string;
  profiles: AmazonAdsProfile[];
}

/** Step 2a — get the Amazon authorize URL + CSRF state for a real store. */
export async function getAmazonAdsAuthorizeUrl(region: string, storeId: string) {
  const query = new URLSearchParams({ region, storeId });
  return request<AmazonAdsAuthUrl>(`/platform-connections/amazon-ads/authorize-url?${query.toString()}`);
}

/** Step 2b — exchange the returned code+state for the list of ad profiles. */
export async function amazonAdsCallback(code: string, state: string) {
  return request<AmazonAdsCallbackResult>('/platform-connections/amazon-ads/callback', {
    method: 'POST',
    body: JSON.stringify({ code, state }),
  });
}

/** Step 3 — bind a selected profile to a store. */
export async function bindAmazonAdsProfile(payload: {
  storeId: string;
  profileId: string;
  region: string;
  marketplaceId?: string;
  sellerId?: string;
  accountName?: string;
}) {
  return request<any>('/platform-connections/amazon-ads/bind', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

// ─── API Sync Jobs ─────────────────────────────────────────────────────────

export async function fetchSyncJobs(params?: {
  platform?: string;
  status?: string;
  syncType?: string;
  page?: number;
  pageSize?: number;
}) {
  const query = new URLSearchParams();
  if (params?.platform) query.set('platform', params.platform);
  if (params?.status) query.set('status', params.status);
  if (params?.syncType) query.set('syncType', params.syncType);
  if (params?.page) query.set('page', String(params.page));
  if (params?.pageSize) query.set('pageSize', String(params.pageSize));
  const qs = query.toString();
  return request<any>(`/api-sync/jobs${qs ? `?${qs}` : ''}`);
}

export async function fetchStoreSyncJobs(
  storeId: string,
  params?: { status?: string; page?: number; pageSize?: number },
) {
  const query = new URLSearchParams();
  if (params?.status) query.set('status', params.status);
  if (params?.page) query.set('page', String(params.page));
  if (params?.pageSize) query.set('pageSize', String(params.pageSize));
  const qs = query.toString();
  return request<any>(`/stores/${storeId}/sync-jobs${qs ? `?${qs}` : ''}`);
}

export async function retrySyncJob(jobId: string) {
  return request<any>(`/api-sync/jobs/${jobId}/retry`, { method: 'POST' });
}

export async function cancelSyncJob(jobId: string) {
  return request<any>(`/api-sync/jobs/${jobId}/cancel`, { method: 'POST' });
}

/** Trigger an immediate sync of a given entity type for a store. The platform
 *  connection is resolved server-side (or pass `platform` to disambiguate). */
export async function startStoreSync(storeId: string, payload: { entityType: string; platform?: string; connectionId?: string }) {
  return request<any>(`/stores/${storeId}/sync`, {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

// ─── Sync Logs ─────────────────────────────────────────────────────────────

export async function fetchSyncLogs(params?: {
  jobId?: string;
  level?: string;
  search?: string;
  page?: number;
  pageSize?: number;
}) {
  const query = new URLSearchParams();
  if (params?.jobId) query.set('jobId', params.jobId);
  if (params?.level) query.set('level', params.level);
  if (params?.search) query.set('search', params.search);
  if (params?.page) query.set('page', String(params.page));
  if (params?.pageSize) query.set('pageSize', String(params.pageSize));
  const qs = query.toString();
  return request<any>(`/api-sync/logs${qs ? `?${qs}` : ''}`);
}

// ─── Warehouses ─────────────────────────────────────────────────────────

export async function fetchWarehouses() {
  // The backend resolves the organization from the authenticated user.
  const res = await request<any>(`/warehouse/locations?pageSize=200`);
  const items = (res?.items ?? res?.records ?? res) as any[];
  return (Array.isArray(items) ? items : []).map((location) => ({
    ...location,
    name: location.name ?? location.locationName ?? '',
    code: location.code ?? location.locationCode ?? '',
    type: location.type ?? location.locationType ?? 'local',
    address: location.address ?? '',
    country: location.country ?? '',
    capacity: Number(location.capacity ?? 0),
    status: location.status ?? 'active',
  }));
}

// ─── Suppliers ──────────────────────────────────────────────────────────

export async function fetchSuppliers(storeId?: string) {
  const qs = storeId ? `?storeId=${storeId}` : '';
  return requestList(`/procurement/suppliers${qs}`);
}

// ─── Purchase Orders ────────────────────────────────────────────────────

export async function fetchPurchaseOrders(storeId?: string) {
  const qs = storeId ? `?storeId=${storeId}` : '';
  return requestList(`/procurement/purchase-orders${qs}`);
}

// ─── Reviews ────────────────────────────────────────────────────────────

export async function fetchReviews(storeId?: string) {
  const qs = storeId ? `?storeId=${storeId}` : '';
  return requestList(`/reviews${qs}`);
}

export async function respondToReview(id: string, responseText: string) {
  return request<any>(`/reviews/${id}/respond`, {
    method: 'POST',
    body: JSON.stringify({ responseText }),
  });
}

// ─── FBA Shipments ──────────────────────────────────────────────────────

export async function fetchFbaShipments(storeId?: string) {
  const qs = storeId ? `?storeId=${storeId}` : '';
  return requestList(`/logistics/shipments${qs}`);
}

/**
 * List Shipment_Exception (异常) entries for the requester's Active_Store
 * (GET /api/logistics/exceptions, Req 9.7). The backend scopes the result to
 * the active store. The FBA shipments list uses this to flag shipments that
 * have one or more open exceptions (Req 9.6); a shipment is flagged when it has
 * an entry whose `resolutionState` is `open`. (See {@link ShipmentException}.)
 */
export async function fetchActiveStoreShipmentExceptions(storeId?: string) {
  const qs = storeId ? '?storeId=' + encodeURIComponent(storeId) : '';
  return requestList<ShipmentException>('/logistics/exceptions' + qs);
}

// ─── Shipment detail (logistics depth: legs/cartons/customs/tracking/exceptions/cost-chain/FBA) ──

/** A single shipment record (header detail). Mirrors backend ShipmentVo. */
export interface Shipment {
  id: string;
  storeId?: string;
  shipmentId?: string;
  shipmentType?: string;
  status?: string;
  carrier?: string;
  trackingNumber?: string;
  shipFromAddress?: string;
  shipToAddress?: string;
  shipDate?: string;
  estimatedDeliveryDate?: string;
  actualDeliveryDate?: string;
  totalWeight?: number;
  weightUnit?: string;
  totalItems?: number;
  shippingCost?: number;
  currency?: string;
  notes?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** One ordered transport leg (头程/尾程). Mirrors backend ShipmentLegVo. */
export interface ShipmentLeg {
  id: string;
  shipmentId: string;
  legType: string;
  sequenceNo: number;
  carrierId?: string;
  carrierName?: string;
  departureDate?: string;
  arrivalDate?: string;
  legCost?: number;
  createdAt?: string;
  updatedAt?: string;
}

/** A box/carton specification (箱规). Mirrors backend CartonSpecVo. */
export interface CartonSpec {
  id: string;
  shipmentId: string;
  boxLengthCm?: number;
  boxWidthCm?: number;
  boxHeightCm?: number;
  boxWeightKg?: number;
  unitsPerBox?: number;
  boxCount?: number;
  createdAt?: string;
  updatedAt?: string;
}

/** Aggregated carton totals. Mirrors backend CartonTotalsVo. */
export interface CartonTotals {
  shipmentId: string;
  totalBoxCount: number;
  totalUnitQuantity: number;
}

/** A managed carrier (承运商). Mirrors backend CarrierVo. */
export interface Carrier {
  id: string;
  orgId?: string;
  name: string;
  serviceType?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** Customs clearance (清关). Mirrors backend CustomsClearanceVo. */
export interface CustomsClearance {
  id?: string;
  shipmentId: string;
  /** one of not-started | declared | in-review | cleared | held */
  clearanceStatus: string;
  declarationRef?: string;
  dutiesTaxes?: number;
  createdAt?: string;
  updatedAt?: string;
}

/** A tracking trajectory entry (轨迹). Mirrors backend TrackingEventVo. */
export interface TrackingEvent {
  id: string;
  shipmentId: string;
  legId?: string;
  eventTime?: string;
  recordedAt?: string;
  description?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** A shipment exception (异常). Mirrors backend ShipmentExceptionVo. */
export interface ShipmentException {
  id: string;
  shipmentId: string;
  exceptionType?: string;
  description?: string;
  /** one of open | resolved */
  resolutionState?: string;
  resolvedBy?: string;
  resolvedAt?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** A handling-cost line (费用). Mirrors backend HandlingCostVo. */
export interface HandlingCost {
  id: string;
  shipmentId: string;
  amount?: number;
  currencyCode?: string;
  description?: string;
  exchangeRate?: number;
  costDate?: string;
}

/** A converted/native cost component within the cost chain. */
export interface CostComponent {
  legId?: string;
  id?: string;
  amount?: number;
  originalAmount?: number | null;
  originalCurrency?: string | null;
  rate?: number | null;
}

/** Itemized end-to-end cost chain (费用链路). Mirrors backend CostChainVo. */
export interface CostChain {
  shipmentId: string;
  reportingCurrency?: string;
  legCosts?: CostComponent[];
  customsDutiesTaxes?: number;
  handlingCosts?: CostComponent[];
  totalLandedCost?: number;
}

/** A single FBA shipment line item. */
export interface FbaLineItem {
  id?: string;
  shipmentId?: string;
  sku?: string;
  msku?: string;
  asin?: string;
  quantity?: number;
}

/** FBA core fields + line items. Mirrors backend FbaFieldsVo. */
export interface FbaFields {
  shipmentId: string;
  fbaShipmentId?: string;
  amazonShipmentStatus?: string;
  destinationFcCode?: string;
  lineItems?: FbaLineItem[];
}

/** GET a single shipment header by id. */
export async function fetchShipment(id: string) {
  return request<Shipment>(`/logistics/shipments/${id}`);
}

/** GET shipment legs ordered by sequence number (Req 4.4). */
export async function fetchShipmentLegs(shipmentId: string) {
  return requestList<ShipmentLeg>(`/logistics/shipments/${shipmentId}/legs`);
}

/** GET carton specs for a shipment. */
export async function fetchCartonSpecs(shipmentId: string) {
  return requestList<CartonSpec>(`/logistics/shipments/${shipmentId}/cartons`);
}

/** GET computed carton totals (Req 5.3, 5.4). */
export async function fetchCartonTotals(shipmentId: string) {
  return request<CartonTotals>(`/logistics/shipments/${shipmentId}/cartons/totals`);
}

/** GET customs clearance; returns a not-started state when absent (Req 7.6). */
export async function fetchCustomsClearance(shipmentId: string) {
  return request<CustomsClearance>(`/logistics/shipments/${shipmentId}/customs`);
}

/** GET tracking events ordered most-recent first (Req 8.4, 8.7). */
export async function fetchTrackingEvents(shipmentId: string) {
  return requestList<TrackingEvent>(`/logistics/shipments/${shipmentId}/tracking`);
}

/** GET handling-cost lines for a shipment (Req 18.3). */
export async function fetchHandlingCosts(shipmentId: string) {
  return requestList<HandlingCost>(`/logistics/shipments/${shipmentId}/handling-costs`);
}

/** GET the itemized cost chain + total (Req 10.3). */
export async function fetchCostChain(shipmentId: string) {
  return request<CostChain>(`/logistics/shipments/${shipmentId}/cost-chain`);
}

/** GET FBA core fields + line items (Req 16.4, 16.6). */
export async function fetchFbaFields(shipmentId: string) {
  return request<FbaFields>(`/logistics/shipments/${shipmentId}/fba`);
}

/**
 * GET exceptions for the Active_Store, filtered client-side to a shipment.
 * The backend exposes exceptions at the store level (GET /logistics/exceptions);
 * the detail page narrows them to the shipment it is showing (Req 9.6).
 */
export async function fetchShipmentExceptions(shipmentId: string) {
  const all = await requestList<ShipmentException>(`/logistics/exceptions`);
  return all.filter((e) => e.shipmentId === shipmentId);
}


// ─── AI Settings ─────────────────────────────────────────────────────────────

export interface AiSettings {
  id?: string;
  provider: string;
  baseUrl: string;
  apiKeyMasked?: string | null;
  apiKeyConfigured?: boolean;
  model: string;
  temperature?: number;
  maxTokens?: number;
  enabled?: boolean;
  extraHeaders?: Record<string, string> | null;
}

export async function fetchAiSettings(): Promise<AiSettings> {
  return request<AiSettings>('/settings/ai');
}

export async function updateAiSettings(payload: Partial<AiSettings> & { apiKey?: string }): Promise<AiSettings> {
  return request<AiSettings>('/settings/ai', {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

export async function testAiConnection(): Promise<{ result: string }> {
  return request<{ result: string }>('/settings/ai/test', { method: 'POST' });
}


// ─── Permissions (RBAC catalog) ─────────────────────────────────────────────

export interface PermissionItem {
  id: string;
  code: string;
  name: string;
  module: string;
  action: string;
  description?: string | null;
  createdAt?: string;
}

/**
 * Fetches the full permission catalog from `GET /api/permissions`. The backend
 * returns a raw JSON array of permissions ordered by module/action; this
 * normalizes to an array so the page can group safely. An optional AbortSignal
 * lets callers enforce a request timeout (Req 4.6).
 */
export async function fetchPermissions(options?: { signal?: AbortSignal }) {
  return requestList<PermissionItem>('/permissions', options);
}

// ─── Users (management actions) ────────────────────────────────────────────

export async function createUser(payload: {
  orgId?: string;
  name: string;
  email: string;
  passwordHash?: string;
  phone?: string;
  status?: string;
}) {
  return request<any>('/users', { method: 'POST', body: JSON.stringify(payload) });
}

export async function updateUser(id: string, payload: Record<string, any>) {
  return request<any>(`/users/${id}`, { method: 'PATCH', body: JSON.stringify(payload) });
}

export async function setUserStatus(id: string, status: string) {
  return updateUser(id, { status });
}

export async function resetUserPassword(id: string, newPassword: string) {
  // updateUser hashes passwordHash server-side.
  return updateUser(id, { passwordHash: newPassword });
}


// ─── Departments (management actions) ──────────────────────────────────────

export async function createDepartment(payload: { orgId?: string; name: string; code?: string }) {
  return request<any>('/departments', { method: 'POST', body: JSON.stringify(payload) });
}

export async function updateDepartment(id: string, payload: Record<string, any>) {
  return request<any>(`/departments/${id}`, { method: 'PATCH', body: JSON.stringify(payload) });
}

export async function deleteDepartment(id: string) {
  return request<any>(`/departments/${id}`, { method: 'DELETE' });
}


// ─── Warehouses (management actions) ───────────────────────────────────────

export async function createWarehouseLocation(payload: {
  orgId?: string;
  locationName: string;
  locationCode?: string;
  locationType?: string;
  address?: string;
  country?: string;
  capacity?: number;
}) {
  return request<any>('/warehouse/locations', { method: 'POST', body: JSON.stringify(payload) });
}

export async function updateWarehouseLocation(id: string, payload: Record<string, any>) {
  return request<any>(`/warehouse/locations/${id}`, { method: 'PUT', body: JSON.stringify(payload) });
}

export async function updateSupplier(id: string, payload: Record<string, any>) {
  return request<any>(`/procurement/suppliers/${id}`, { method: 'PUT', body: JSON.stringify(payload) });
}

export async function updatePurchaseOrderStatus(id: string, status: string) {
  return request<any>(`/procurement/purchase-orders/${id}/status?status=${encodeURIComponent(status)}`, {
    method: 'PUT',
  });
}

export async function updateShipmentStatus(id: string, status: string) {
  return request<any>(`/logistics/shipments/${id}/status?status=${encodeURIComponent(status)}`, {
    method: 'PUT',
  });
}

// ─── Feishu Integration (group binding + notification rules) ───────────────

export interface FeishuIntegration {
  id: string;
  orgId?: string;
  storeId?: string | null;
  provider?: string;
  appId?: string;
  connectionType?: string;
  botOpenId?: string | null;
  defaultChatId?: string | null;
  status?: string;
  lastConnectedAt?: string | null;
  createdAt?: string;
}

export interface FeishuChatBinding {
  id: string;
  feishuIntegrationId: string;
  storeId?: string | null;
  chatId: string;
  chatType?: string | null;
  chatName?: string | null;
  notifyOnApproval?: boolean;
  notifyOnExecution?: boolean;
  notifyOnRollback?: boolean;
  notifyOnRiskAlert?: boolean;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface FeishuNotificationRule {
  id: string;
  feishuIntegrationId: string;
  storeId?: string | null;
  chatId?: string | null;
  name: string;
  eventType: string;
  conditionJson?: string | null;
  enabled?: boolean;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** List Feishu integrations. The backend paginates; normalize to an array. */
export async function fetchFeishuIntegrations() {
  return requestList<FeishuIntegration>('/integrations/feishu?page=1&pageSize=100');
}

/** Connect / create a new Feishu integration (Req 10.5). */
export async function connectFeishuIntegration(payload: {
  orgId?: string;
  storeId?: string | null;
  provider?: string;
  connectionType?: string;
  appId?: string;
  appSecret?: string;
  defaultChatId?: string | null;
}) {
  return request<FeishuIntegration>('/integrations/feishu/connect', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

/** Quick-connect a Feishu custom-bot webhook (one-way push; no app creds). */
export async function connectFeishuWebhook(payload: {
  orgId?: string;
  storeId?: string;
  name?: string;
  webhookUrl: string;
  webhookSecret?: string;
}) {
  return request<FeishuIntegration>('/integrations/feishu/connect-webhook', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

/** Send a sample interactive confirm card to exercise the approve/reject flow. */
export async function testFeishuConfirm(id: string, storeId?: string) {
  const qs = storeId ? `?storeId=${encodeURIComponent(storeId)}` : '';
  return request<{ dispatched: boolean; actionRequestId: string }>(
    `/integrations/feishu/${id}/test-confirm${qs}`, { method: 'POST' });
}

/** Update an existing Feishu integration (Req 10.5). */
export async function updateFeishuIntegration(id: string, payload: {
  orgId?: string;
  storeId?: string | null;
  provider?: string;
  connectionType?: string;
  appId?: string;
  appSecret?: string;
  defaultChatId?: string | null;
}) {
  return request<FeishuIntegration>(`/integrations/feishu/${id}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

/** Send a test message through a Feishu integration. */
export async function sendFeishuTestMessage(id: string, chatId?: string) {
  const qs = chatId ? `?chatId=${encodeURIComponent(chatId)}` : '';
  return request<void>(`/integrations/feishu/${id}/test-message${qs}`, { method: 'POST' });
}

/** List the chat bindings for a Feishu integration (Req 10.1). */
export async function fetchFeishuChatBindings(integrationId: string) {
  return requestList<FeishuChatBinding>(`/integrations/feishu/${integrationId}/chat-bindings`);
}

/** Bind a Feishu group chat and return the saved binding (Req 10.2). */
export async function createFeishuChatBinding(integrationId: string, payload: {
  storeId?: string;
  chatId: string;
  chatType?: string;
  chatName?: string;
  notifyOnApproval?: boolean;
  notifyOnExecution?: boolean;
  notifyOnRollback?: boolean;
  notifyOnRiskAlert?: boolean;
}) {
  return request<FeishuChatBinding>(`/integrations/feishu/${integrationId}/chat-bindings`, {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

/** List the notification rules for a Feishu integration (Req 10.3). */
export async function fetchFeishuNotificationRules(integrationId: string) {
  return requestList<FeishuNotificationRule>(`/integrations/feishu/${integrationId}/notification-rules`);
}

/** Save a notification rule and return the saved rule (Req 10.4). */
export async function createFeishuNotificationRule(integrationId: string, payload: {
  storeId?: string;
  chatId?: string;
  name: string;
  eventType: string;
  conditionJson?: string;
  enabled?: boolean;
}) {
  return request<FeishuNotificationRule>(`/integrations/feishu/${integrationId}/notification-rules`, {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

// ─── Insight Agent (Req 24) ────────────────────────────────────────────────────

/** Result of an Insight Agent query: insights + recommended actions (Req 24.1). */
export interface InsightResult {
  query: string;
  source: string;
  premium: boolean;
  insights: string;
  recommendedActions: string[];
  generatedBy: string;
}

export interface InsightQueryInput {
  query: string;
  storeId?: string;
  source?: string;
  premium?: boolean;
}

/** Submit a store-scoped Insight Agent query with source/premium flags (Req 24.1, 24.3). */
export async function submitInsightQuery(input: InsightQueryInput) {
  return request<InsightResult>('/insight-agent/query', {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

/** Suggested prompts shown when the Insight Agent page loads (Req 24.2). */
export async function fetchInsightSuggestions() {
  return requestList<string>('/insight-agent/suggestions');
}

// ─── Data Insights / SQP / AMC surfaces (Req 30) ───────────────────────────────

export interface ProductListItem {
  productId?: string;
  parentAsin?: string;
  asin?: string;
  sku?: string;
  name?: string;
  adSales?: number;
  adSpend?: number;
  tacos?: number;
  totalSales?: number;
  totalOrders?: number;
  inventory?: number;
  inventoryStatus?: string;
}

export interface CustomReportItem {
  id: string;
  title?: string;
  type?: string;
  periodStart?: string;
  periodEnd?: string;
  createdAt?: string;
}

export interface ProductInsights {
  currency?: string;
  items: ProductListItem[];
  customReportConsumed: number;
  customReportTotal: number;
  customReports: CustomReportItem[];
}

export interface BrandMetrics {
  requiresActivation: boolean;
  message?: string;
  totalBrandCustomers?: number | null;
  engagementRate?: number | null;
  conversionRate?: number | null;
  newToBrandSalesShare?: number | null;
}

export interface MarketReport {
  id: string;
  name?: string;
  category?: string;
  updatedAt?: string;
}

export interface MarketInsights {
  requiresActivation: boolean;
  message?: string;
  reports: MarketReport[];
}

export interface SqpRow {
  searchQuery?: string;
  impressions?: number;
  impressionShare?: number;
  brandClickRate?: number;
  marketClickRate?: number;
  brandAddToCartRate?: number;
  marketAddToCartRate?: number;
  brandConversionRate?: number;
  marketConversionRate?: number;
}

export interface SqpInsights {
  requiresActivation: boolean;
  message?: string;
  rows: SqpRow[];
}

export interface AmcTemplate {
  key: string;
  name?: string;
  description?: string;
  category?: string;
  activationRequired: boolean;
}

export interface AmcTemplates {
  activated: boolean;
  message?: string;
  templates: AmcTemplate[];
}

interface InsightsParams {
  storeId?: string;
  startDate?: string;
  endDate?: string;
}

function insightsQuery(params?: InsightsParams): string {
  const query = new URLSearchParams();
  if (params?.storeId) query.set('storeId', params.storeId);
  if (params?.startDate) query.set('startDate', params.startDate);
  if (params?.endDate) query.set('endDate', params.endDate);
  const qs = query.toString();
  return qs ? `?${qs}` : '';
}

/** Product list metrics + custom-report quota (Req 30.1, 30.2). */
export async function fetchInsightsProductList(params?: InsightsParams) {
  return request<ProductInsights>(`/insights/product-list${insightsQuery(params)}`);
}

/** Category-brand metrics; gated behind brand-analytics activation (Req 30.3). */
export async function fetchInsightsBrandMetrics(params?: InsightsParams) {
  return request<BrandMetrics>(`/insights/brand-metrics${insightsQuery(params)}`);
}

/** Market-monitoring reports (Req 30.4). */
export async function fetchInsightsMarketInsights(storeId?: string) {
  return request<MarketInsights>(`/insights/market-insights${insightsQuery({ storeId })}`);
}

/** Brand-versus-market search-query funnel metrics (Req 30.5). */
export async function fetchInsightsSqp(params?: InsightsParams) {
  return request<SqpInsights>(`/insights/sqp${insightsQuery(params)}`);
}

/** AMC analytical model library templates (Req 30.6, 30.7). */
export async function fetchAmcModels(storeId?: string) {
  return request<AmcTemplates>(`/insights/amc/models${insightsQuery({ storeId })}`);
}

/** AMC audience-creation templates (Req 30.6, 30.7). */
export async function fetchAmcAudiences(storeId?: string) {
  return request<AmcTemplates>(`/insights/amc/audiences${insightsQuery({ storeId })}`);
}

// ─── All Search Ads workspace tabs (Req 19.1) ─────────────────────────────────
// Store-scoped, data-backed surfaces for the remaining workspace tabs in
// CampaignsPage: ad groups, promoted products, negative targeting, purchased
// products, bid adjustments, SP budget caps, and the operation log. Each reuses
// an existing backing table; see the advertising module controllers.

/** An ad group row (广告组) with parent campaign + keyword-derived performance. */
export interface AdGroupVo {
  id: string;
  campaignId?: string;
  campaignName?: string | null;
  storeId?: string;
  name: string;
  status: string;
  defaultBid: number;
  keywordCount: number;
  spend: number;
  sales: number;
  orders: number;
  acos: number;
  externalId?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

/** List ad groups for the active store, optionally narrowed to one campaign. */
export async function fetchAdGroups(params?: {
  storeId?: string;
  campaignId?: string;
  status?: string;
  page?: number;
  pageSize?: number;
}) {
  const query = new URLSearchParams();
  if (params?.storeId) query.set('storeId', params.storeId);
  if (params?.campaignId) query.set('campaignId', params.campaignId);
  if (params?.status) query.set('status', params.status);
  if (params?.page) query.set('page', String(params.page));
  query.set('pageSize', String(params?.pageSize ?? 200));
  const qs = query.toString();
  return requestList<AdGroupVo>(`/ad-groups${qs ? `?${qs}` : ''}`);
}

/** A promoted-product / purchased-product row (ASIN/SKU + aggregated performance). */
export interface ProductAdVo {
  asin?: string | null;
  sku?: string | null;
  campaignName?: string | null;
  adGroupName?: string | null;
  impressions: number;
  clicks: number;
  spend: number;
  sales: number;
  orders: number;
  acos: number;
  ctr: number;
  cvr: number;
}

/** List promoted products (推广商品) aggregated from the advertised-product report. */
export async function fetchProductAds(params?: { storeId?: string; campaignId?: string }) {
  const query = new URLSearchParams();
  if (params?.storeId) query.set('storeId', params.storeId);
  if (params?.campaignId) query.set('campaignId', params.campaignId);
  const qs = query.toString();
  return requestList<ProductAdVo>(`/product-ads${qs ? `?${qs}` : ''}`);
}

/** List products purchased through the store's ads (购买的其他商品). */
export async function fetchOtherProducts(params?: { storeId?: string; campaignId?: string }) {
  const query = new URLSearchParams();
  if (params?.storeId) query.set('storeId', params.storeId);
  if (params?.campaignId) query.set('campaignId', params.campaignId);
  const qs = query.toString();
  return requestList<ProductAdVo>(`/other-products${qs ? `?${qs}` : ''}`);
}

/** A negative keyword/target row (否定投放). */
export interface NegativeKeywordVo {
  id: string;
  campaignId?: string | null;
  campaignName?: string | null;
  adGroupId?: string | null;
  storeId?: string;
  keywordText: string;
  matchType?: string | null;
  /** Scope: campaign / ad group. */
  level?: string | null;
  source?: string | null;
  status?: string | null;
  externalId?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

/** List negative keywords/targets for the active store, optionally per campaign. */
export async function fetchNegativeKeywords(params?: {
  storeId?: string;
  campaignId?: string;
  level?: string;
  page?: number;
  pageSize?: number;
}) {
  const query = new URLSearchParams();
  if (params?.storeId) query.set('storeId', params.storeId);
  if (params?.campaignId) query.set('campaignId', params.campaignId);
  if (params?.level) query.set('level', params.level);
  if (params?.page) query.set('page', String(params.page));
  query.set('pageSize', String(params?.pageSize ?? 200));
  const qs = query.toString();
  return requestList<NegativeKeywordVo>(`/negative-keywords${qs ? `?${qs}` : ''}`);
}

/** A bid-adjustment / bid-change row (竞价调整). */
export interface BidChangeVo {
  id: string;
  storeId?: string;
  campaignId?: string | null;
  campaignName?: string | null;
  keywordId?: string | null;
  targetId?: string | null;
  entityType?: string | null;
  oldBid?: number | null;
  newBid?: number | null;
  changeReason?: string | null;
  automated: boolean;
  createdAt?: string | null;
}

/** List recent bid adjustments (manual + automated) for the active store. */
export async function fetchBidChanges(params?: {
  storeId?: string;
  campaignId?: string;
  entityType?: string;
  page?: number;
  pageSize?: number;
}) {
  const query = new URLSearchParams();
  if (params?.storeId) query.set('storeId', params.storeId);
  if (params?.campaignId) query.set('campaignId', params.campaignId);
  if (params?.entityType) query.set('entityType', params.entityType);
  if (params?.page) query.set('page', String(params.page));
  query.set('pageSize', String(params?.pageSize ?? 200));
  const qs = query.toString();
  return requestList<BidChangeVo>(`/bid-changes${qs ? `?${qs}` : ''}`);
}

/** An operation-log row (操作日志) reused from automation executions. */
export interface OperationLogVo {
  id: string;
  storeId?: string;
  source?: string | null;
  entityType?: string | null;
  entityId?: string | null;
  actionType?: string | null;
  beforeSnapshot?: string | null;
  afterSnapshot?: string | null;
  riskLevel?: string | null;
  status?: string | null;
  errorMessage?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

/**
 * List the operation/change log for the active store (操作日志), reusing the
 * automation-executions feed which records timestamp, action, entity, source,
 * and before/after snapshots.
 */
export async function fetchOperationLog(params?: {
  storeId?: string;
  status?: string;
  page?: number;
  pageSize?: number;
}) {
  const query = new URLSearchParams();
  if (params?.storeId) query.set('storeId', params.storeId);
  if (params?.status) query.set('status', params.status);
  if (params?.page) query.set('page', String(params.page));
  query.set('pageSize', String(params?.pageSize ?? 100));
  const qs = query.toString();
  return requestList<OperationLogVo>(`/automation/executions${qs ? `?${qs}` : ''}`);
}

// ─── Procurement / Supplier / Logistics — create actions ───────────────────────
// Appended (do not reorder): wires the "新增供应商 / 新增采购订单 / 新增 FBA 货件"
// create flows to their existing backend POST endpoints, all using the standard
// ApiResponse envelope unwrapped by request().

/** Payload for creating a supplier (POST /api/suppliers). orgId is resolved
 *  server-side from the authenticated user when omitted. */
export interface CreateSupplierInput {
  supplierName: string;
  contactName?: string;
  contactEmail?: string;
  contactPhone?: string;
  address?: string;
  country?: string;
  paymentTerms?: string;
  leadTimeDays?: number;
  rating?: number;
  notes?: string;
}

/** Create a new supplier and return the saved record. */
export async function createSupplier(input: CreateSupplierInput) {
  return request<any>('/procurement/suppliers', {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

/** A line item on a new purchase order. */
export interface CreatePurchaseOrderItemInput {
  sku?: string;
  asin?: string;
  productName?: string;
  quantityOrdered: number;
  unitCost?: number;
  notes?: string;
}

/** Payload for creating a purchase order (POST /api/procurement/purchase-orders). */
export interface CreatePurchaseOrderInput {
  storeId: string;
  supplierId: string;
  poNumber: string;
  status?: string;
  currency?: string;
  orderDate?: string;
  expectedDeliveryDate?: string;
  shippingMethod?: string;
  trackingNumber?: string;
  notes?: string;
  items?: CreatePurchaseOrderItemInput[];
}

/** Create a new purchase order and return the saved record. */
export async function createPurchaseOrder(input: CreatePurchaseOrderInput) {
  return request<any>('/procurement/purchase-orders', {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

/** Payload for creating an FBA shipment (POST /api/logistics/shipments). */
export interface CreateFbaShipmentInput {
  storeId: string;
  shipmentId?: string;
  shipmentType?: string;
  carrier?: string;
  trackingNumber?: string;
  shipFromAddress?: string;
  shipToAddress?: string;
  shipDate?: string;
  estimatedDeliveryDate?: string;
  totalItems?: number;
  shippingCost?: number;
  currency?: string;
  notes?: string;
}

/** Create a new FBA shipment and return the saved record. */
export async function createFbaShipment(input: CreateFbaShipmentInput) {
  return request<any>('/logistics/shipments', {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

// ─── Store-scoped product picker (Rank Monitoring multi-store, Req 28) ──────────

/**
 * Minimal product shape used by store-scoped pickers/filters (e.g. the Rank
 * Monitoring page). Products are always scoped to a single store so different
 * stores stay managed distinctly.
 */
export interface StoreProductOption {
  id: string;
  storeId: string;
  name: string;
  sku?: string | null;
  asin?: string | null;
}

/**
 * List the products that belong to a store for use in a picker/filter. Unlike
 * {@link fetchProducts} this requests a large page so the full product set for
 * the store is available client-side (the products list endpoint is paginated).
 */
export async function fetchStoreProductOptions(storeId: string) {
  const params = new URLSearchParams();
  params.set('storeId', storeId);
  params.set('page', '1');
  params.set('pageSize', '500');
  return requestList<StoreProductOption>(`/products?${params.toString()}`);
}

// ─── Automation Rule Template Links (应用到 / 关联对象, Req 25.3) ─────────────────

/**
 * One object linked to a rule template (campaign / target / keyword), with a
 * resolved display name for the "应用到 / 关联对象" panel.
 */
export interface RuleTemplateLink {
  id: string;
  objectType: string;
  objectId: string;
  objectName?: string | null;
  createdAt?: string | null;
}

/** List the objects currently linked to a rule template (Req 25.3). */
export async function listRuleTemplateLinks(templateId: string) {
  return requestList<RuleTemplateLink>(`/automation/rule-templates/${templateId}/links`);
}

/**
 * Link many objects to a rule template at once (Req 25.3). Each link is
 * {@code { objectType, objectId }}; {@code objectType} accepts
 * {@code campaign} / {@code target} / {@code keyword}, plus {@code product}
 * which the backend resolves to the product's campaigns.
 */
export async function linkRuleTemplateObjectsBulk(
  templateId: string,
  links: { objectType: string; objectId: string }[],
) {
  return request<RuleTemplate>(`/automation/rule-templates/${templateId}/links`, {
    method: 'POST',
    body: JSON.stringify({ links }),
  });
}

/** Remove a single linked object from a rule template (Req 25.3). */
export async function unlinkRuleTemplateObject(templateId: string, linkId: string) {
  return request<RuleTemplate>(`/automation/rule-templates/${templateId}/links/${linkId}`, {
    method: 'DELETE',
  });
}

// ─── AI Hosting optimizer — manual trigger (Req 21.2) ──────────────────────────

/** Result of one AI hosting optimization tick. */
export interface HostingOptimizeSummary {
  campaignsProcessed: number;
  campaignsFailed: number;
  bidsChanged: number;
}

/**
 * Run one AI hosting optimization tick on demand (Req 21.2). Moves hosted-campaign
 * keyword bids toward Target_ACoS within automation-policy bounds and writes
 * bid_changes / automation_executions rows that surface in the 操作日志 tab and
 * the AI Actions dashboard. A {0,0,0} result means there is no hosted campaign
 * with recent performance data to act on yet.
 */
export async function runHostingOptimizer() {
  return request<HostingOptimizeSummary>('/automation/hosting/run', { method: 'POST' });
}

// ─── Insight Agent saved insights (item 16) ────────────────────────────────────

/** A persisted Insight Agent result rendered in the saved-insights history (item 16). */
export interface SavedInsight {
  id: string;
  storeId?: string | null;
  query: string;
  source?: string;
  premium: boolean;
  insights: string;
  recommendedActions: string[];
  generatedBy?: string;
  createdAt?: string | null;
}

/** List saved insights for a store, newest first (item 16). */
export async function fetchSavedInsights(storeId?: string) {
  const qs = storeId ? `?storeId=${storeId}` : '';
  return requestList<SavedInsight>(`/insight-agent/insights${qs}`);
}

/** Manually delete a saved insight by id (item 16). */
export async function deleteSavedInsight(id: string) {
  return request<void>(`/insight-agent/insights/${id}`, { method: 'DELETE' });
}

// ─── AI Notifications → Feishu push (item 19) ──────────────────────────────────

/** Push an AI notification to the store's bound Feishu chat(s) via the Feishu integration (item 19). */
export async function pushAiNotificationToFeishu(id: string) {
  return request<AiNotification>(`/ai-notifications/${id}/push-feishu`, { method: 'POST' });
}

// ─── Data source activation (item 8) ───────────────────────────────────────────

/** Per-store activation status for an external data source (item 8). */
export interface DataSourceActivation {
  storeId: string;
  source: string;
  activated: boolean;
  activatedAt?: string | null;
  message?: string;
}

/** Get the activation status for a per-store data source (item 8). Source defaults to brand_analytics. */
export async function fetchDataSourceActivation(storeId: string, source = 'brand_analytics') {
  return request<DataSourceActivation>(
    `/insights/brand-source/activation?storeId=${encodeURIComponent(storeId)}&source=${encodeURIComponent(source)}`,
  );
}

/** Activate a per-store data source, unlocking the brand / SQP / AMC surfaces (item 8). */
export async function activateDataSource(storeId: string, source = 'brand_analytics') {
  return request<DataSourceActivation>(
    `/insights/brand-source/activate?storeId=${encodeURIComponent(storeId)}&source=${encodeURIComponent(source)}`,
    { method: 'POST' },
  );
}

// ─── Table Views: Saved Views (Req 2.11, 2.13, 2.14, 17.5) ─────────────────────
// Per-user, store-independent saved views backed by TableViewController
// (`/api/table-views`). A Saved_View is a named combination of a table's column
// configuration, filters, and sort order, scoped to (userId, tableKey). The
// backend validates the name (1–100 chars, unique per user+tableKey) and rejects
// empty/over-length/duplicate names with a readable `error.message`, which
// request() rethrows as an Error the caller can render (Req 2.13).

/**
 * A persisted Saved_View as returned by the backend {@code SavedViewVo}. The
 * {@code config} field is an opaque JSON string holding the view's column
 * configuration, filters, and sort order (serialized by the frontend).
 */
export interface SavedView {
  id: string;
  userId?: string | null;
  tableKey: string;
  name: string;
  /** Column configuration + filters + sort order, serialized as JSON. */
  config: string;
  createdAt?: string | null;
  updatedAt?: string | null;
}

/** Body for {@code POST /api/table-views} (Req 2.11). */
export interface SavedViewSaveInput {
  tableKey: string;
  name: string;
  /** Serialized column/filter/sort JSON; defaults to `{}` server-side when omitted. */
  config?: string;
}

/**
 * List the current user's saved views for a table key, newest-or-name-ordered by
 * the backend (Req 2.11). Returns an array (the endpoint returns a raw list).
 */
export async function fetchSavedViews(tableKey: string) {
  return requestList<SavedView>(`/table-views?tableKey=${encodeURIComponent(tableKey)}`);
}

/**
 * Save the current columns/filters/sort as a named Saved_View (Req 2.11). The
 * backend rejects an empty, over-length (>100), or duplicate name for the same
 * (user, tableKey) and the rejection surfaces as a thrown Error carrying the
 * backend's message (Req 2.13).
 */
export async function saveSavedView(input: SavedViewSaveInput) {
  return request<SavedView>('/table-views', {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

/** Delete one of the current user's saved views by id. */
export async function deleteSavedView(id: string) {
  return request<void>(`/table-views/${id}`, { method: 'DELETE' });
}

// ─── Table Views: Column Configuration (Req 2.12) ──────────────────────────────
// Per-user, store-independent column configuration backed by TableViewController
// (`/api/table-views/columns`), scoped to (userId, tableKey). The `config` field
// is an opaque JSON string holding column visibility/order/pin state, serialized
// by the frontend. Mirrors the backend {@code ColumnConfigVo}.

/**
 * A persisted Column_Configuration as returned by the backend
 * {@code ColumnConfigVo}. The {@code config} field is an opaque JSON string the
 * frontend serializes/deserializes; it may be empty when the user has not yet
 * customized the columns for this table.
 */
export interface ColumnConfig {
  id?: string | null;
  userId?: string | null;
  tableKey: string;
  /** Column visibility/order/pin state, serialized as JSON (may be empty). */
  config?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

/**
 * Load the current user's column configuration for a table key (Req 2.12).
 * Returns null when the user has not yet saved a configuration for the table.
 */
export async function fetchColumnConfig(tableKey: string) {
  return request<ColumnConfig | null>(
    `/table-views/columns?tableKey=${encodeURIComponent(tableKey)}`,
  );
}

/**
 * Persist the current user's column configuration for a table key (Req 2.12).
 * The `config` string is the serialized column visibility/order state.
 */
export async function saveColumnConfig(input: { tableKey: string; config: string }) {
  return request<ColumnConfig>('/table-views/columns', {
    method: 'PUT',
    body: JSON.stringify(input),
  });
}

// ─── Amazon Ads AI Hosting System (Req 11, 12, 21, 22, 24, 26, 28, 29) ─────────
// Client for the hosting controllers under `/api/advertising/hosting`. These VOs
// serialize their JSON with explicit snake_case keys (Jackson @JsonProperty), so
// the TypeScript shapes below intentionally use snake_case to match the wire
// contract exactly. All functions follow the existing request/requestList
// pattern (auth + readable-error handling come from request()).

// ── Hosting configuration (Req 21.1, 21.2, 21.5, 12.1, 12.2) ──

/**
 * Persisted hosting configuration for a scope (store / goal / campaign), as
 * returned by {@code GET/PUT /hosting/config/...}. When no row exists for the
 * scope the value fields are null and the UI falls back to inherited defaults.
 */
export interface HostingConfig {
  scope: string;
  scope_id: string;
  store_id: string;
  active_phase?: string | null;
  default_personality?: string | null;
  execution_mode?: string | null;
  auto_execute_threshold?: number | null;
  emergency_auto_action_enabled?: boolean | null;
  shadow_mode?: boolean | null;
  notification_preferences?: Record<string, any> | null;
  /** Safety-boundary overrides keyed by SafetyBoundaryLimit name (e.g. MAX_BID). */
  boundary_overrides?: Record<string, number> | null;
}

/**
 * Body for {@code PUT /hosting/config/{storeId}} (Req 21.2, 12.2). All fields are
 * optional; only supplied (non-null) fields are overlaid onto the persisted config.
 * Backend validation enforces valid enums, thresholds in [0,1], and only-tighten
 * boundaries (Req 21.3, 21.4, 12.6) and surfaces failures as a thrown Error.
 */
export interface HostingConfigInput {
  active_phase?: string;
  default_personality?: string;
  execution_mode?: string;
  auto_execute_threshold?: number;
  emergency_auto_action_enabled?: boolean;
  shadow_mode?: boolean;
  notification_preferences?: Record<string, any>;
  boundary_overrides?: Record<string, number>;
}

/** Load a store's hosting config (Req 21.1). */
export async function fetchHostingConfig(storeId: string) {
  return request<HostingConfig>(`/advertising/hosting/config/${encodeURIComponent(storeId)}`);
}

/** Load a goal-level hosting config override (Req 21.5). */
export async function fetchHostingGoalConfig(storeId: string, goalId: string) {
  return request<HostingConfig>(
    `/advertising/hosting/config/${encodeURIComponent(storeId)}/goals/${encodeURIComponent(goalId)}`,
  );
}

/** Load a campaign-level hosting config override (Req 21.5). */
export async function fetchHostingCampaignConfig(storeId: string, campaignId: string) {
  return request<HostingConfig>(
    `/advertising/hosting/config/${encodeURIComponent(storeId)}/campaigns/${encodeURIComponent(campaignId)}`,
  );
}

/** Validate and persist a store's hosting config (Req 21.2, 21.3, 21.4, 12.2, 12.6). */
export async function saveHostingConfig(storeId: string, data: HostingConfigInput) {
  return request<HostingConfig>(`/advertising/hosting/config/${encodeURIComponent(storeId)}`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
}

// ── Canary rollout controls (Req 35.2) ──

/** Org-level canary rollout state resolved through the active store. */
export interface HostingCanaryRollout {
  org_id: string;
  enabled: boolean;
  store_ids: string[];
}

/** Load the canary rollout state for the active store's organization. */
export async function fetchHostingCanary(storeId: string) {
  return request<HostingCanaryRollout>(`/advertising/hosting/canary/${encodeURIComponent(storeId)}`);
}

/** Enable org-level canary rollout. Only stores in `store_ids` can execute while enabled. */
export async function enableHostingCanary(storeId: string) {
  return request<HostingCanaryRollout>(`/advertising/hosting/canary/${encodeURIComponent(storeId)}/enable`, {
    method: 'POST',
  });
}

/** Disable org-level canary rollout, promoting all stores back to normal routing. */
export async function disableHostingCanary(storeId: string) {
  return request<HostingCanaryRollout>(`/advertising/hosting/canary/${encodeURIComponent(storeId)}/disable`, {
    method: 'POST',
  });
}

/** Include a store in the active organization's canary set. */
export async function addHostingCanaryStore(storeId: string, targetStoreId = storeId) {
  return request<HostingCanaryRollout>(
    `/advertising/hosting/canary/${encodeURIComponent(storeId)}/stores/${encodeURIComponent(targetStoreId)}`,
    { method: 'POST' },
  );
}

/** Remove a store from the active organization's canary set. */
export async function removeHostingCanaryStore(storeId: string, targetStoreId = storeId) {
  return request<HostingCanaryRollout>(
    `/advertising/hosting/canary/${encodeURIComponent(storeId)}/stores/${encodeURIComponent(targetStoreId)}`,
    { method: 'DELETE' },
  );
}

// ── Brand words (Req 22.1, 22.2, 22.6) ──

/** A protected brand word that is never proposed as a negative keyword (Req 22.1). */
export interface BrandWord {
  id: string;
  store_id: string;
  word: string;
  /** Match type: exact (full match) or contains (substring match). */
  match_type: string;
  created_at?: string | null;
}

/** Body for {@code POST /hosting/brand-words/{storeId}} (Req 22.2). */
export interface BrandWordInput {
  word: string;
  /** exact (full) or contains (substring); defaults to exact server-side when omitted. */
  match_type?: string;
}

/** List a store's brand words (Req 22.2). */
export async function fetchBrandWords(storeId: string) {
  return request<BrandWord[]>(`/advertising/hosting/brand-words/${encodeURIComponent(storeId)}`);
}

/** Add a brand word to a store's protection list (Req 22.2, 22.6). */
export async function addBrandWord(storeId: string, data: BrandWordInput) {
  return request<BrandWord>(`/advertising/hosting/brand-words/${encodeURIComponent(storeId)}`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

/** Remove a brand word from a store's protection list (Req 22.2, 22.6). */
export async function deleteBrandWord(storeId: string, wordId: string) {
  return request<void>(
    `/advertising/hosting/brand-words/${encodeURIComponent(storeId)}/${encodeURIComponent(wordId)}`,
    { method: 'DELETE' },
  );
}

// ── Dashboard summary, decisions, analytics, health (Req 11, 27, 29, 30.5) ──

/** Per-campaign learning-period status (Req 19.5). */
export interface HostingLearningPeriod {
  campaign_id: string;
  days_remaining: number;
  total_days: number;
}

/**
 * Summary cards for {@code GET /hosting/dashboard/summary} (Req 11.1, 27.1).
 * Savings figures are estimates derived from effect attribution and are labelled
 * as such via {@code estimated_savings_label} (Req 11.3, 27.3).
 */
export interface HostingDashboardSummary {
  store_id: string;
  hosted_campaigns_count: number;
  today_decisions_count: number;
  awaiting_approval_count: number;
  effective_today_count: number;
  failed_today_count: number;
  estimated_savings_7d?: number | null;
  estimated_savings_30d?: number | null;
  estimated_savings_label?: string;
  learning_period_campaigns_count: number;
  learning_periods?: HostingLearningPeriod[] | null;
}

/** A decision list-item for {@code GET /hosting/decisions} (Req 11.4). */
export interface HostingDecision {
  id: string;
  store_id: string;
  campaign_id?: string | null;
  campaign_name?: string | null;
  /** Producing engine: v1_bid, v2_budget, v3_keyword. */
  engine?: string | null;
  /** bid_adjustment, budget_adjustment, keyword_addition, negative_keyword_addition. */
  decision_type?: string | null;
  execution_mode?: string | null;
  routing_outcome?: string | null;
  risk_score?: number | null;
  field?: string | null;
  before_value?: any;
  after_value?: any;
  promoted_operation_id?: string | null;
  sync_state?: string | null;
  created_at?: string | null;
  expires_at?: string | null;
}

/** The immutable decision snapshot captured at decision time (Req 34, 13). */
export interface HostingDecisionSnapshot {
  dataCutoff?: string | null;
  lookbackDays?: number;
  metricInputs?: Record<string, number>;
  dqGateResult?: { passed: boolean; reason?: string | null } | null;
  personality?: string | null;
  inheritanceChain?: string[];
  effectiveBoundaries?: { limitName: string; value: string; sourceLevel: string }[];
  riskFormulaVersion?: string | null;
  riskScore?: number | null;
  ruleVersion?: string | null;
  currency?: string | null;
  marketplaceTimezone?: string | null;
  executionMode?: string | null;
  killSwitchActive?: boolean;
  shadowModeActive?: boolean;
}

/** One effect-attribution row for the decision explanation card (Req 11.5, 13, 8.2). */
export interface HostingEffectAttribution {
  metric_type: string;
  observed_change?: number | null;
  /** Honest baseline-aware estimate; null when no reliable baseline exists (Req 8.2). */
  estimated_incremental_impact?: number | null;
  attribution_confidence?: number | null;
  attribution_method?: string | null;
  method_version?: string | null;
  measurement_window_start?: string | null;
  measurement_window_end?: string | null;
}

/** Detail view for {@code GET /hosting/decisions/{id}} (Req 11.5, 13). */
export interface HostingDecisionDetail {
  decision: HostingDecision;
  snapshot?: HostingDecisionSnapshot | null;
  attributions?: HostingEffectAttribution[] | null;
}

/** A failure reason and its count in the analytics view (Req 29.2). */
export interface HostingFailureReason {
  reason: string;
  count: number;
}

/** Per-engine analytics breakdown (Req 29.4). */
export interface HostingEngineBreakdown {
  engine: string;
  total_decisions: number;
  attempted_count: number;
  effective_count: number;
  success_rate?: number | null;
  estimated_spend_saved?: number | null;
}

/**
 * Historical analytics for {@code GET /hosting/analytics} (Req 29). Impact figures
 * are estimates derived from effect attribution and labelled via {@code impact_label}.
 */
export interface HostingAnalytics {
  store_id: string;
  /** Requested period: 7d, 30d, or 90d. */
  period: string;
  total_decisions: number;
  auto_executed_count: number;
  approval_required_count: number;
  attempted_count: number;
  effective_count: number;
  success_rate?: number | null;
  average_risk_score?: number | null;
  top_failure_reasons?: HostingFailureReason[] | null;
  average_acos_improvement?: number | null;
  total_estimated_spend_saved?: number | null;
  total_estimated_sales_lift?: number | null;
  average_attribution_confidence?: number | null;
  per_engine?: HostingEngineBreakdown[] | null;
  impact_label?: string;
}

/** Dependency health for {@code GET /hosting/health} (Req 30.5). */
export interface HostingHealth {
  /** Aggregate status: the worst of the per-dependency statuses. */
  overall: string;
  /** Per-dependency status keyed by dependency name (amazon_api, inventory_service, redis, feishu). */
  dependencies: Record<string, string>;
  checked_at?: string | null;
}

/** Summary cards for the hosting dashboard, scoped to a store (Req 11.1, 27.1). */
export async function fetchHostingDashboardSummary(storeId: string) {
  return request<HostingDashboardSummary>(
    `/advertising/hosting/dashboard/summary?storeId=${encodeURIComponent(storeId)}`,
  );
}

/** Recent AI decisions for a store, newest first (Req 11.4). */
export async function fetchHostingDecisions(storeId: string, limit = 50) {
  const params = new URLSearchParams();
  params.set('storeId', storeId);
  params.set('limit', String(limit));
  return request<HostingDecision[]>(`/advertising/hosting/decisions?${params.toString()}`);
}

/** A single decision's explanation card (snapshot + attributions) (Req 11.5, 13). */
export async function fetchHostingDecision(id: string) {
  return request<HostingDecisionDetail>(`/advertising/hosting/decisions/${encodeURIComponent(id)}`);
}

/** Historical hosting analytics for a store over a period (Req 29). */
export async function fetchHostingAnalytics(storeId: string, period = '7d') {
  const params = new URLSearchParams();
  params.set('storeId', storeId);
  params.set('period', period);
  return request<HostingAnalytics>(`/advertising/hosting/analytics?${params.toString()}`);
}

/** Hosting dependency health (Req 30.5). */
export async function fetchHostingHealth() {
  return request<HostingHealth>('/advertising/hosting/health');
}

// ── Optimization trigger and run detail (Req 26, 28) ──

/** Body for {@code POST /hosting/optimize/trigger} (Req 28.1). */
export interface HostingOptimizeTriggerInput {
  storeId: string;
  /** Optional single campaign; omitted optimizes all hosted campaigns in the store (Req 28.2, 28.3). */
  campaignId?: string;
}

/** Synchronous trigger response (HTTP 202) carrying the run id to poll (Req 28.1, 28.5). */
export interface HostingOptimizeTriggerResult {
  run_id: string;
  /** Run status at trigger time (always running). */
  status: string;
  request_id?: string | null;
}

/** Run detail for {@code GET /hosting/optimization-runs/{runId}} (Req 28.7). */
export interface HostingOptimizationRun {
  run_id: string;
  store_id?: string | null;
  /** scheduled or manual. */
  trigger_type?: string | null;
  /** running, completed, or failed. */
  status?: string | null;
  phase?: string | null;
  campaigns_processed?: number | null;
  campaigns_skipped?: number | null;
  operations_created?: number | null;
  decisions_generated?: number | null;
  decisions_auto_executed?: number | null;
  decisions_requiring_approval?: number | null;
  /** Per-campaign result detail (campaign_id → result), parsed JSON. */
  per_campaign_results?: any;
  /** Per-campaign skip reasons (campaign_id → reason), parsed JSON. */
  skip_reasons?: any;
  started_at?: string | null;
  completed_at?: string | null;
  request_id?: string | null;
}

/**
 * Start a manual optimization run (Req 28.1). Returns 202 + run_id immediately;
 * the per-campaign work runs asynchronously. The backend enforces a per-store
 * minimum manual interval and surfaces validation/interval failures as a thrown
 * Error carrying the structured HOSTING_* message (Req 26.4, 28.4).
 */
export async function triggerHostingOptimization(data: HostingOptimizeTriggerInput) {
  return request<HostingOptimizeTriggerResult>('/advertising/hosting/optimize/trigger', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

/** Poll a triggered optimization run's status / per-campaign results (Req 28.7). */
export async function fetchHostingOptimizationRun(runId: string) {
  return request<HostingOptimizationRun>(
    `/advertising/hosting/optimization-runs/${encodeURIComponent(runId)}`,
  );
}

// ── Operation lifecycle: approve / reject / rollback / phase (Req 24, 10.6, 17.5) ──

/** Response for the approve/reject endpoints carrying the post-transition state (Req 24.5). */
export interface HostingOperationAction {
  operation_id: string;
  /** approved or rejected. */
  action: string;
  /** The Operation's resolved sync state after the action. */
  sync_state?: string | null;
}

/**
 * Response for {@code POST /hosting/operations/{id}/rollback} (Req 10.6). When
 * {@code confirmation_required} is true, overlapping subsequent operations were
 * detected and the caller must re-issue with {@code confirm: true}; otherwise the
 * compensating operation's identity and state are returned.
 */
export interface HostingRollbackResult {
  confirmation_required: boolean;
  conflicting_operation_ids?: string[] | null;
  warning_message?: string | null;
  compensating_operation_id?: string | null;
  sync_state?: string | null;
}

/** Response for the admin phase-change endpoint (Req 17.5). */
export interface HostingPhaseChangeResult {
  store_id: string;
  previous_phase?: string | null;
  active_phase?: string | null;
}

/** Approve an awaiting-approval Operation, advancing it to pending (Req 24.2). */
export async function approveHostingOperation(id: string) {
  return request<HostingOperationAction>(
    `/advertising/hosting/operations/${encodeURIComponent(id)}/approve`,
    { method: 'POST' },
  );
}

/** Reject an awaiting-approval Operation with a reason, cancelling it (Req 24.3). */
export async function rejectHostingOperation(id: string, reason: string) {
  return request<HostingOperationAction>(
    `/advertising/hosting/operations/${encodeURIComponent(id)}/reject`,
    { method: 'POST', body: JSON.stringify({ reason }) },
  );
}

/**
 * Roll back an effective, reversible Operation by creating a compensating
 * Operation (Req 10.6). Pass {@code confirm=true} to proceed despite overlapping
 * subsequent operations flagged by a prior {@code confirmation_required} response.
 */
export async function rollbackHostingOperation(id: string, confirm = false) {
  const qs = confirm ? '?confirm=true' : '';
  return request<HostingRollbackResult>(
    `/advertising/hosting/operations/${encodeURIComponent(id)}/rollback${qs}`,
    { method: 'POST' },
  );
}

/** Perform an adjacent-only hosting phase transition (V1↔V2↔V3) for a store (Req 17.5). */
export async function changeHostingPhase(storeId: string, phase: string) {
  return request<HostingPhaseChangeResult>('/advertising/hosting/phase', {
    method: 'POST',
    body: JSON.stringify({ store_id: storeId, phase }),
  });
}


// ─── Google Ads module (platform-workspace-rbac Req 6, 7, 8) ───────────────────
// The 独立站 Google Ads workspace: read campaigns + performance reports, submit
// manual campaign-create / bid-budget-status adjustments through the platform
// Operation pipeline, and trigger an AI-hosting pass. Reads return a tri-state
// envelope so the UI can branch on connect-prompt (Req 6.6), error+retry
// (Req 6.5), and ok (Req 6.3, 6.4) deterministically.

/** The three read states the GoogleAds_Module must distinguish (Req 6). */
export type GoogleAdsReadState = 'OK' | 'CONNECT_PROMPT' | 'ERROR';

/** Tri-state envelope returned by the Google Ads read endpoints. */
export interface GoogleAdsReadResponse<T> {
  state: GoogleAdsReadState;
  data?: T | null;
  message?: string | null;
}

/** A single Google Ads campaign with aggregated metrics (Req 6.3). */
export interface GoogleAdsCampaign {
  campaignId: string;
  name: string;
  status: string;
  budget?: number | null;
  impressions: number;
  clicks: number;
  cost?: number | null;
  conversions: number;
  conversionValue?: number | null;
}

/** One day's aggregated Google Ads performance row (Req 6.4). */
export interface GoogleAdsPerformanceRow {
  date: string;
  impressions: number;
  clicks: number;
  cost?: number | null;
  conversions: number;
  conversionValue?: number | null;
}

/** A date-ranged Google Ads performance report with range totals (Req 6.4). */
export interface GoogleAdsPerformanceReport {
  from: string;
  to: string;
  rows: GoogleAdsPerformanceRow[];
  totalImpressions: number;
  totalClicks: number;
  totalCost?: number | null;
  totalConversions: number;
  totalConversionValue?: number | null;
}

/** Response of a manual Google Ads write — the created Operation's identity and
 *  resolved Sync_State, used to surface the pending change (Req 7.6). */
export interface GoogleAdsOperationResult {
  operationId?: string | null;
  logicalOperationId?: string | null;
  storeId?: string | null;
  operationScope?: string | null;
  syncState?: string | null;
  entityType?: string | null;
  entityId?: string | null;
  coalesced: boolean;
}

/** Summary of one Google Ads AI-hosting pass (Req 8). */
export interface GoogleAdsHostingRunSummary {
  processed: number;
  skipped: number;
  failed: number;
  operationsCreated: number;
  skips: { campaignId: string; reason: string }[];
}

/** GET campaigns for a Store's active Google Ads connection (Req 6.3, 6.5, 6.6). */
export async function fetchGoogleAdsCampaigns(storeId: string) {
  return request<GoogleAdsReadResponse<GoogleAdsCampaign[]>>(
    `/google-ads/campaigns?storeId=${encodeURIComponent(storeId)}`,
  );
}

/** GET a date-ranged Google Ads performance report (Req 6.4, 6.5, 6.6). */
export async function fetchGoogleAdsReport(storeId: string, params?: { from?: string; to?: string }) {
  const query = new URLSearchParams({ storeId });
  if (params?.from) query.set('from', params.from);
  if (params?.to) query.set('to', params.to);
  return request<GoogleAdsReadResponse<GoogleAdsPerformanceReport>>(
    `/google-ads/reports?${query.toString()}`,
  );
}

// ─── TikTok Ads (read) ──────────────────────────────────────────────────────
// Symmetric to the Google Ads read surface above: tri-state envelope so the
// 列表/报告 views can show a connect prompt or error+retry without conflating
// either with a transport failure. Bound to the tiktok-family store's TikTok Ads
// account; never fabricates data when not connected.

export type TikTokAdsReadState = 'OK' | 'CONNECT_PROMPT' | 'ERROR';

export interface TikTokAdsReadResponse<T> {
  state: TikTokAdsReadState;
  data?: T | null;
  message?: string | null;
}

export interface TikTokAdsCampaign {
  campaignId: string;
  name: string;
  status?: string | null;
  budget?: number | null;
  impressions: number;
  clicks: number;
  cost: number;
  conversions: number;
  conversionValue: number;
}

export interface TikTokAdsPerformanceRow {
  date: string;
  impressions: number;
  clicks: number;
  cost: number;
  conversions: number;
  conversionValue: number;
}

export interface TikTokAdsPerformanceReport {
  from: string;
  to: string;
  rows: TikTokAdsPerformanceRow[];
  totalImpressions: number;
  totalClicks: number;
  totalCost: number;
  totalConversions: number;
  totalConversionValue: number;
}

/** GET campaigns for a tiktok-family Store's active TikTok Ads connection. */
export async function fetchTikTokAdsCampaigns(storeId: string) {
  return request<TikTokAdsReadResponse<TikTokAdsCampaign[]>>(
    `/tiktok-ads/campaigns?storeId=${encodeURIComponent(storeId)}`,
  );
}

/** GET a date-ranged TikTok Ads performance report (from/to optional). */
export async function fetchTikTokAdsReport(storeId: string, params?: { from?: string; to?: string }) {
  const query = new URLSearchParams({ storeId });
  if (params?.from) query.set('from', params.from);
  if (params?.to) query.set('to', params.to);
  return request<TikTokAdsReadResponse<TikTokAdsPerformanceReport>>(
    `/tiktok-ads/report?${query.toString()}`,
  );
}

/** POST a manual Google Ads campaign creation as a platform_mutation Operation (Req 7.1). */
export async function createGoogleAdsCampaign(payload: {
  storeId: string;
  name: string;
  budget?: number | null;
  status?: string;
  campaignId?: string;
  logicalIdempotencyKey?: string;
}) {
  return request<GoogleAdsOperationResult>('/google-ads/operations/campaigns', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

/** POST a manual Google Ads bid / budget / status adjustment (Req 7.2). */
export async function adjustGoogleAds(payload: {
  storeId: string;
  entityType?: string;
  entityId: string;
  changeType: 'bid' | 'budget' | 'status';
  beforeValue?: string;
  afterValue: string;
  logicalIdempotencyKey?: string;
  expectedVersion?: number;
}) {
  return request<GoogleAdsOperationResult>('/google-ads/operations/adjust', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

/** POST a Google Ads AI-hosting optimization pass for a Store (Req 8). */
export async function runGoogleAdsHosting(storeId: string) {
  return request<GoogleAdsHostingRunSummary>(
    `/google-ads/hosting/optimize?storeId=${encodeURIComponent(storeId)}`,
    { method: 'POST' },
  );
}

/** POST — bind a Google Ads account to an independent-site Store (Req 5.5). */
export async function bindGoogleAdsConnection(payload: {
  storeId: string;
  connectionName?: string;
  config: Record<string, string>;
}) {
  return request<any>('/independent-site/connect/google-ads', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

// ─── Multistore AI Ads Operations (multistore-ai-ads-operations) ───────────────

/**
 * Payload for {@code POST /api/product-ads/campaign} — the per-product AI ad
 * creation modal (Req 1.2). Mirrors the backend {@code ProductAdCampaignRequest}
 * (camelCase JSON). Provide {@code productId} and/or {@code parentAsin} to link
 * the resulting campaign to the product. The budget and any supplied target
 * ACoS / bid bounds / budget bounds must be positive, and each upper bound must
 * be no smaller than its lower bound (Req 1.4); the backend rejects violations
 * before persisting anything. {@code executionMode} is optional — when omitted
 * the backend falls back to {@code observe_only} (Req 1.3).
 */
export interface ProductAdCampaignRequest {
  storeId: string;
  productId?: string;
  parentAsin?: string;
  budget: number;
  budgetType?: string;
  personality?: string;
  hostingEnabled?: boolean;
  targetAcos?: number;
  bidMin?: number;
  bidMax?: number;
  budgetMin?: number;
  budgetMax?: number;
  executionMode?: string;
}

/**
 * Result view for {@code POST /api/product-ads/campaign} (Req 1.10). Mirrors the
 * backend {@code ProductAdCampaignResultVo}, whose fields are serialized as
 * snake_case.
 */
export interface ProductAdCampaignResult {
  campaign_id: string;
  product_id?: string | null;
  parent_asin?: string | null;
  execution_mode: string;
}

/**
 * Read-only Amazon Ads report-sync observability row (Req 2.5, 2.6). Mirrors the
 * backend {@code ProductAdSyncStatusVo} record, whose fields are serialized as
 * camelCase. A failed sync is never mapped to a success: {@code reportStatus} is
 * passed through verbatim and a failed run carries a readable {@code lastError}.
 */
export interface ProductAdSyncStatus {
  storeId: string;
  reportType: string;
  reportStatus: string;
  lastSuccessAt?: string | null;
  lastError?: string | null;
}

/**
 * Per-store independent-site connection / write-capability state (Req 4.4, 4.7).
 * Mirrors the backend {@code IndependentSiteConnectionStateVo}, whose fields are
 * serialized as snake_case. {@code inventory_write_supported} /
 * {@code fulfillment_write_supported} are false when the connector does not
 * expose the capability, so the UI can mark it "暂不支持" instead of offering an
 * entry point that would silently fail.
 */
export interface IndependentSiteConnectionState {
  store_id: string;
  platform: string;
  connection_state: string;
  inventory_write_supported: boolean;
  fulfillment_write_supported: boolean;
  last_success_at?: string | null;
}

/**
 * Create a single-product AI ad campaign (Req 1.2). The backend orchestrates, in
 * one transaction, the keyword campaign creation, product link, and (when
 * {@code hostingEnabled}) the hosting config + safety boundary, then queues the
 * external write via the Operation-Outbox; returns the created campaign id,
 * linked product, and resolved execution mode.
 */
export async function createProductAdCampaign(req: ProductAdCampaignRequest) {
  return request<ProductAdCampaignResult>('/product-ads/campaign', {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

/**
 * Fetch the Amazon Ads report-sync status for a store (Req 2.5). Returns one row
 * per report type with the most recent run status, last successful completion
 * time, and a readable failure reason when the latest run failed.
 */
export async function fetchProductAdSyncStatus(storeId: string) {
  return requestList<ProductAdSyncStatus>(
    `/product-ads/sync-status?storeId=${encodeURIComponent(storeId)}`,
  );
}

/**
 * Per-product view of a campaign associated with a single product (Req 2.1, 2.2,
 * 2.3). Mirrors the backend {@code ProductCampaignVo} (camelCase JSON). The
 * association is resolved through {@code campaign_product_links} and the
 * campaign's {@code parentAsin}; performance metrics are aggregated from
 * {@code performance_daily}. {@code dataStatus} faithfully passes through the
 * source data status — {@code preliminary} (recent / attribution-pending) or
 * {@code finalized} — and is {@code null} when no performance row exists yet.
 */
export interface ProductCampaign {
  campaignId: string;
  campaignName?: string | null;
  parentAsin?: string | null;
  productId?: string | null;
  status?: string | null;
  hostingEnabled?: boolean;
  spend: number;
  clicks: number;
  orders: number;
  sales: number;
  acos: number;
  dataStatus?: string | null;
}

/**
 * List the advertising campaigns associated with a single product, viewed
 * product-first (Req 2.1, 2.2, 2.3). Supply {@code parentAsin} and/or
 * {@code productId} to identify the product; results are confined to the
 * requester's data scope (Req 2.7).
 */
export async function fetchProductCampaigns(params: {
  storeId: string;
  parentAsin?: string;
  productId?: string;
}) {
  const query = new URLSearchParams();
  query.set('storeId', params.storeId);
  if (params.parentAsin) query.set('parentAsin', params.parentAsin);
  if (params.productId) query.set('productId', params.productId);
  return requestList<ProductCampaign>(`/product-ads/campaigns?${query.toString()}`);
}

/**
 * Fetch the independent-site connection / write-capability state per store for a
 * store scope (Req 4.4). Returns one row per independent-site store/platform with
 * its connection state and inventory/fulfillment write-capability flags.
 *
 * <p>When {@code storeId} is omitted the backend returns the connection state for
 * <em>every</em> independent-site store within the account's data scope — used by
 * the 独立站工作台 hub to list all connected Shopify / WooCommerce stores at once.
 */
export async function fetchIndependentSiteConnectionState(storeId?: string) {
  const qs = storeId ? `?storeId=${encodeURIComponent(storeId)}` : '';
  return requestList<IndependentSiteConnectionState>(
    `/independent-site/connection-state${qs}`,
  );
}

/**
 * Result of an independent-site write-back enqueue (Req 4.1, 4.2). Mirrors the
 * backend {@code OperationActionVo}, whose fields are serialized as snake_case.
 * The external Shopify / WooCommerce platform is never called on the request
 * thread; this returns the queued Operation's id and its sync state.
 */
export interface IndependentSiteWriteResult {
  operation_id: string;
  action: string;
  sync_state?: string | null;
}

/**
 * Payload for {@code POST /api/independent-site/products/{productId}/inventory}
 * (Req 4.1, 4.2). Mirrors the backend {@code InventoryUpdateRequest} (camelCase
 * JSON). {@code storeId} is required for the Store_Group_Scope / Platform_Family
 * boundary checks (Req 4.6); {@code quantity} is the new absolute available
 * quantity (zero or greater). {@code sku} / {@code locationId} are optional and
 * target a specific variant / platform inventory location.
 */
export interface InventoryUpdateRequest {
  storeId: string;
  quantity: number;
  sku?: string;
  locationId?: string;
}

/**
 * Payload for {@code POST /api/independent-site/orders/{orderId}/fulfillment}
 * (Req 4.1, 4.2). Mirrors the backend {@code FulfillmentRequest} (camelCase
 * JSON). {@code storeId} is required for the Store_Group_Scope / Platform_Family
 * boundary checks (Req 4.6); the shipment tracking fields are optional.
 */
export interface FulfillmentRequest {
  storeId: string;
  trackingNumber?: string;
  carrier?: string;
  trackingUrl?: string;
  notifyCustomer?: boolean;
}

/**
 * Enqueue an inventory update for an independent-site product (Req 4.1, 4.2).
 * The backend gates the write on the store's connection state / capabilities and
 * routes it through the Operation-Outbox for asynchronous submission — it never
 * calls the external platform synchronously, and refuses (HTTP 403/409) when the
 * store has no valid connection or lacks credentials (Req 4.3).
 */
export async function updateIndependentSiteInventory(
  productId: string,
  req: InventoryUpdateRequest,
) {
  return request<IndependentSiteWriteResult>(
    `/independent-site/products/${encodeURIComponent(productId)}/inventory`,
    { method: 'POST', body: JSON.stringify(req) },
  );
}

/**
 * Enqueue a fulfillment / shipment mark for an independent-site order (Req 4.1,
 * 4.2). The backend gates the write on the store's connection state /
 * capabilities and routes it through the Operation-Outbox for asynchronous
 * submission — it never calls the external platform synchronously, and refuses
 * (HTTP 403/409) when the store has no valid connection or lacks credentials
 * (Req 4.3).
 */
export async function markIndependentSiteFulfillment(
  orderId: string,
  req: FulfillmentRequest,
) {
  return request<IndependentSiteWriteResult>(
    `/independent-site/orders/${encodeURIComponent(orderId)}/fulfillment`,
    { method: 'POST', body: JSON.stringify(req) },
  );
}

// ─── TikTok Shop write-back (inventory / fulfillment) ───────────────────────
// Symmetric to the independent-site write-back above, targeting the tiktok 平台族
// and the tiktok_shop platform. Reuses the same request/result shapes (the
// backend reuses InventoryUpdateRequest / FulfillmentRequest / OperationActionVo
// / IndependentSiteConnectionStateVo). Honesty: the backend gates on the store's
// tiktok_shop connection + write capability and routes through the
// Operation-Outbox; it refuses (403/409) when not connected / not capable.

export async function fetchTikTokConnectionState(storeId?: string) {
  const qs = storeId ? `?storeId=${encodeURIComponent(storeId)}` : '';
  return requestList<IndependentSiteConnectionState>(`/tiktok/connection-state${qs}`);
}

export async function updateTikTokInventory(productId: string, req: InventoryUpdateRequest) {
  return request<IndependentSiteWriteResult>(
    `/tiktok/products/${encodeURIComponent(productId)}/inventory`,
    { method: 'POST', body: JSON.stringify(req) },
  );
}

export async function markTikTokFulfillment(orderId: string, req: FulfillmentRequest) {
  return request<IndependentSiteWriteResult>(
    `/tiktok/orders/${encodeURIComponent(orderId)}/fulfillment`,
    { method: 'POST', body: JSON.stringify(req) },
  );
}
