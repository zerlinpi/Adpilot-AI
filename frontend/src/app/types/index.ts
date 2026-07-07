// AdPilot AI - Core Type Definitions

// ─── Enums ────────────────────────────────────────────────────────────
export type GoalType = 'launch' | 'profit' | 'growth' | 'brand_defense' | 'competitor' | 'category' | 'clearance' | 'rank_boost';
export type GoalStatus = 'active' | 'paused' | 'completed';
export type CampaignType = 'auto' | 'manual_keyword' | 'pat' | 'brand' | 'competitor' | 'category';
export type CampaignStatus = 'active' | 'paused' | 'learning' | 'limited_budget' | 'needs_review';
export type MatchType = 'exact' | 'phrase' | 'broad';
export type EntityStatus = 'active' | 'paused' | 'archived';
export type TargetType = 'asin' | 'category' | 'brand';
export type HarvestingStatus = 'candidate' | 'add_exact' | 'add_phrase' | 'add_broad' | 'add_negative' | 'watchlist' | 'waste';
export type RecommendationStatus = 'pending' | 'applied' | 'dismissed' | 'watching';
export type RiskLevel = 'low' | 'medium' | 'high';
export type ChangeSource = 'manual' | 'ai_suggested' | 'auto_applied';
export type ReportType = 'daily' | 'weekly' | 'monthly';
export type EntityType = 'campaign' | 'ad_group' | 'keyword' | 'target';
export type OptimizeFrequency = 'daily' | 'hourly_12' | 'hourly';
export type RiskPreference = 'conservative' | 'balanced' | 'aggressive';
export type StoreStatus = 'connected' | 'disconnected' | 'error';
export type MarketplaceCode = 'US' | 'UK' | 'DE' | 'JP' | 'CA' | 'AU' | 'FR' | 'IT' | 'ES' | 'MX';
export type RecommendationType =
  | 'increase_budget' | 'decrease_budget'
  | 'increase_bid' | 'decrease_bid'
  | 'add_keyword' | 'add_negative_keyword'
  | 'add_competitor_asin' | 'pause_target'
  | 'launch_boost' | 'move_to_exact'
  | 'split_campaign' | 'inventory_warning'
  | 'high_acos_warning' | 'low_impression_warning'
  | 'rank_opportunity';

// ─── Core Entities ────────────────────────────────────────────────────
export interface User {
  id: string;
  email: string;
  name: string;
  avatarUrl?: string;
  role: 'admin' | 'manager' | 'viewer';
  orgId: string;
}

export interface Organization {
  id: string;
  name: string;
  plan: 'free' | 'starter' | 'growth' | 'enterprise';
}

export interface Marketplace {
  id: string;
  code: MarketplaceCode;
  name: string;
  currency: string;
  flag: string;
}

export interface Store {
  id: string;
  orgId: string;
  name: string;
  marketplaceId: string;
  marketplace?: Marketplace;
  marketplaceName?: string;
  marketplaceCode?: string;
  marketplaceCurrency?: string;
  marketplaceTimezone?: string;
  sellerId: string;
  status: StoreStatus;
  /** Normalized platform family: amazon | shopify | woocommerce | tiktok. */
  platform?: string;
  /** Store platform group: marketplace | independent_site | unknown. */
  platformGroup?: string;
  /** Default ad platform for this store: amazon_ads | google_ads | tiktok_ads | none. */
  adPlatform?: string;
  /** Optional backend grouping label for store switcher/card display. */
  storeGroup?: string | null;
}

export interface Product {
  id: string;
  storeId: string;
  sku: string;
  asin: string;
  name: string;
  imageUrl?: string;
  price: number;
  cost: number;
  grossMargin: number;
  inventory: number;
  targetAcos: number;
  breakEvenAcos: number;
  category: string;
  brand: string;
  status: 'active' | 'paused' | 'out_of_stock';
}

// ─── Goal & Campaign ─────────────────────────────────────────────────
export interface Goal {
  id: string;
  storeId: string;
  name: string;
  type: GoalType;
  status: GoalStatus;
  targetAcos: number;
  dailyBudget: number;
  maxCpc: number;
  minBid: number;
  maxBid: number;
  brandKeywords: string[];
  categoryKeywords: string[];
  competitorBrands: string[];
  competitorAsins: string[];
  autoNegate: boolean;
  autoBid: boolean;
  autoExpand: boolean;
  optimizeFrequency: OptimizeFrequency;
  riskPreference: RiskPreference;
  productIds: string[];
  products?: Product[];
  campaigns?: Campaign[];
  performance?: PerformanceMetrics;
  createdAt: string;
  updatedAt: string;
}

export interface Campaign {
  id: string;
  goalId: string;
  storeId: string;
  name: string;
  type: CampaignType;
  status: CampaignStatus;
  dailyBudget: number;
  budgetUsedToday: number;
  startDate: string;
  endDate?: string;
  adGroups?: AdGroup[];
  performance?: PerformanceMetrics;
  createdAt: string;
}

export interface AdGroup {
  id: string;
  campaignId: string;
  name: string;
  defaultBid: number;
  status: EntityStatus;
  keywords?: Keyword[];
  targets?: Target[];
}

export interface Keyword {
  id: string;
  adGroupId: string;
  keywordText: string;
  matchType: MatchType;
  bid: number;
  status: EntityStatus;
  bidHealthScore: number;
  performance?: PerformanceMetrics;
}

export interface Target {
  id: string;
  adGroupId: string;
  targetType: TargetType;
  targetValue: string;
  bid: number;
  status: EntityStatus;
  performance?: PerformanceMetrics;
}

export interface SearchTerm {
  id: string;
  campaignId: string;
  keywordId?: string;
  searchTerm: string;
  impressions: number;
  clicks: number;
  orders: number;
  spend: number;
  sales: number;
  acos: number;
  ctr: number;
  cvr: number;
  cpc: number;
  harvestingStatus: HarvestingStatus;
  periodStart: string;
  periodEnd: string;
}

export interface NegativeKeyword {
  id: string;
  campaignId: string;
  keywordText: string;
  matchType: 'exact' | 'phrase';
  source: ChangeSource;
}

// ─── Performance & Recommendations ────────────────────────────────────
export interface PerformanceMetrics {
  impressions: number;
  clicks: number;
  orders: number;
  spend: number;
  sales: number;
  acos: number;
  roas: number;
  ctr: number;
  cvr: number;
  cpc: number;
  organicSales?: number;
  adSales?: number;
  tacos?: number;
}

export interface DailyPerformance {
  date: string;
  impressions: number;
  clicks: number;
  orders: number;
  spend: number;
  sales: number;
  acos: number;
  roas: number;
}

export interface Recommendation {
  id: string;
  storeId: string;
  type: RecommendationType;
  title: string;
  description: string;
  reason: string;
  targetEntityType: string;
  targetEntityId: string;
  targetEntityName?: string;
  currentData: Record<string, number | string>;
  expectedImpact: string;
  riskLevel: RiskLevel;
  status: RecommendationStatus;
  createdAt: string;
}

export interface BudgetChange {
  id: string;
  campaignId: string;
  campaignName?: string;
  oldBudget: number;
  newBudget: number;
  reason: string;
  source: ChangeSource;
  createdAt: string;
}

export interface BidChange {
  id: string;
  keywordId: string;
  keywordText?: string;
  oldBid: number;
  newBid: number;
  reason: string;
  source: ChangeSource;
  createdAt: string;
}

export interface Report {
  id: string;
  storeId: string;
  type: ReportType;
  title: string;
  periodStart: string;
  periodEnd: string;
  summary?: string;
  createdAt: string;
}

// ─── Goal Type Config ────────────────────────────────────────────────
export interface GoalTypeConfig {
  type: GoalType;
  label: string;
  description: string;
  icon: string;
  color: string;
  bgColor: string;
  defaultTargetAcos: number;
  defaultRisk: RiskPreference;
  campaignTypes: CampaignType[];
}

// ─── Dashboard ───────────────────────────────────────────────────────
export interface DashboardKPI {
  label: string;
  value: string;
  change: number;
  trend: 'up' | 'down' | 'flat';
  prefix?: string;
  suffix?: string;
}

export interface AISummary {
  id: string;
  severity: 'info' | 'warning' | 'critical';
  message: string;
  action?: string;
  metric?: string;
  value?: number;
}
