import { type ClassValue, clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function formatCurrency(value: number, currency = 'USD'): string {
  const n = Number.isFinite(value) ? value : 0;
  return new Intl.NumberFormat('en-US', { style: 'currency', currency, minimumFractionDigits: 0, maximumFractionDigits: 2 }).format(n);
}

export function formatNumber(value: number): string {
  const n = Number.isFinite(value) ? value : 0;
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return n.toLocaleString();
}

export function formatPercent(value: number, decimals = 1): string {
  const n = Number.isFinite(value) ? value : 0;
  return `${n.toFixed(decimals)}%`;
}

export function getChangeColor(change: number, inverse = false): string {
  const positive = inverse ? change < 0 : change > 0;
  return positive ? 'text-emerald-600' : change === 0 ? 'text-slate-400' : 'text-red-500';
}

export function getChangeBg(change: number, inverse = false): string {
  const positive = inverse ? change < 0 : change > 0;
  return positive ? 'bg-emerald-50 text-emerald-600' : change === 0 ? 'bg-slate-50 text-slate-400' : 'bg-red-50 text-red-500';
}

export function getGoalTypeColor(type: string): string {
  const map: Record<string, string> = {
    launch: 'bg-violet-100 text-violet-700',
    profit: 'bg-emerald-100 text-emerald-700',
    growth: 'bg-blue-100 text-blue-700',
    brand_defense: 'bg-teal-100 text-teal-700',
    competitor: 'bg-red-100 text-red-700',
    category: 'bg-orange-100 text-orange-700',
    clearance: 'bg-indigo-100 text-indigo-700',
    rank_boost: 'bg-yellow-100 text-yellow-700',
  };
  return map[type] || 'bg-slate-100 text-slate-700';
}

export function getCampaignStatusColor(status: string): string {
  const map: Record<string, string> = {
    active: 'bg-emerald-100 text-emerald-700',
    paused: 'bg-slate-100 text-slate-500',
    learning: 'bg-blue-100 text-blue-700',
    limited_budget: 'bg-orange-100 text-orange-700',
    needs_review: 'bg-red-100 text-red-700',
  };
  return map[status] || 'bg-slate-100 text-slate-500';
}

export function getHarvestingStatusColor(status: string): string {
  const map: Record<string, string> = {
    candidate: 'bg-blue-100 text-blue-700',
    add_exact: 'bg-emerald-100 text-emerald-700',
    add_phrase: 'bg-teal-100 text-teal-700',
    add_broad: 'bg-cyan-100 text-cyan-700',
    add_negative: 'bg-red-100 text-red-700',
    watchlist: 'bg-yellow-100 text-yellow-700',
    waste: 'bg-red-100 text-red-700',
  };
  return map[status] || 'bg-slate-100 text-slate-500';
}

export function getRiskColor(risk: string): string {
  const map: Record<string, string> = {
    low: 'bg-emerald-100 text-emerald-700',
    medium: 'bg-yellow-100 text-yellow-700',
    high: 'bg-red-100 text-red-700',
  };
  return map[risk] || 'bg-slate-100 text-slate-500';
}

export function getRecommendationIcon(type: string): string {
  const map: Record<string, string> = {
    increase_budget: 'TrendingUp',
    decrease_budget: 'TrendingDown',
    increase_bid: 'ArrowUp',
    decrease_bid: 'ArrowDown',
    add_keyword: 'Plus',
    add_negative_keyword: 'Minus',
    add_competitor_asin: 'Crosshair',
    pause_target: 'Pause',
    launch_boost: 'Rocket',
    move_to_exact: 'Target',
    split_campaign: 'Split',
    inventory_warning: 'Package',
    high_acos_warning: 'AlertTriangle',
    low_impression_warning: 'Eye',
    rank_opportunity: 'Zap',
  };
  return map[type] || 'Info';
}

export function getSeverityColor(severity: string): string {
  const map: Record<string, string> = {
    info: 'border-blue-200 bg-blue-50',
    warning: 'border-orange-200 bg-orange-50',
    critical: 'border-red-200 bg-red-50',
  };
  return map[severity] || 'border-slate-200 bg-slate-50';
}

export function formatDate(value: string | Date | null | undefined): string {
  if (!value) return '-';
  let d: Date;
  if (typeof value === 'string') {
    // Date-only strings (yyyy-MM-dd) parse as UTC midnight, which shifts to the
    // previous day for users west of UTC. Parse them as local time instead.
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
    d = m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : new Date(value);
  } else {
    d = value;
  }
  if (isNaN(d.getTime())) return '-';
  return d.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' });
}

/** Format an ISO timestamp with date + time-of-day precision (zh-CN). */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '-';
  const d = new Date(value);
  if (isNaN(d.getTime())) return '-';
  return d.toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  });
}
