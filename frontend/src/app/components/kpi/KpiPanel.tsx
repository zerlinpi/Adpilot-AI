// AdPilot AI — collapsible KpiPanel (advertising-workspace-rework Req 30.1, 30.4).
//
// A collapsible region (Req 30.1) presenting the advertising KPI figures for a
// date range plus a period-over-period comparison. By default it shows the last
// 7 days vs the prior 7 days (Req 30.3), with EVERY date boundary computed in
// the Active_Store's Marketplace_Timezone (Req 30.4) via the pure helper in
// `../../lib/advertisingKpiPeriod`.
//
// This component is intentionally standalone and presentational: it receives the
// Marketplace_Timezone and (optionally) the already-fetched metrics, and emits
// the resolved comparison through `onComparisonChange` so the workspace shell
// (task 20.4) can wire it to `useAdvertisingQueryState` and the data layer. It
// does NOT fetch data or own global query state itself.
//
// Honesty rule (Req 30.4 / 51.14): when the Marketplace_Timezone is unset or
// invalid, the panel surfaces a configuration error instead of silently
// defaulting to the server/browser timezone.

import { useEffect, useMemo, useState } from 'react';
import { ChevronDown, TrendingDown, TrendingUp, Minus } from 'lucide-react';

import { cn } from '../../lib/utils';
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from '../ui/collapsible';
import {
  MarketplaceTimezoneError,
  periodOverPeriodChange,
  resolveDefaultPeriodComparison,
  type KpiPeriodComparison,
} from '../../lib/advertisingKpiPeriod';

/** One KPI figure rendered in the panel. */
export interface KpiMetric {
  /** Stable key (used for React keys and as a test hook). */
  key: string;
  /** Display label, e.g. "花费". */
  label: string;
  /** Pre-formatted value for the current period, e.g. "$1,240". */
  value: string;
  /**
   * Raw numeric value of the current period; when both this and
   * {@link priorRaw} are finite the panel computes the period-over-period
   * change itself (Req 30.6 / 19).
   */
  currentRaw?: number;
  /** Raw numeric value of the prior period (see {@link currentRaw}). */
  priorRaw?: number;
  /**
   * When `true`, a decrease is "good" (e.g. ACoS, CPC) and is colored
   * positively; defaults to `false` (an increase is good).
   */
  inverse?: boolean;
}

export interface KpiPanelProps {
  /** Active_Store Marketplace_Timezone (IANA). Unset/invalid -> config error. */
  timeZone: string | null | undefined;
  /**
   * The applied comparison. When omitted, the panel resolves the default
   * last-7 vs prior-7 comparison in `timeZone` (Req 30.3).
   */
  comparison?: KpiPeriodComparison;
  /** Called with the resolved/default comparison once it is computed. */
  onComparisonChange?: (comparison: KpiPeriodComparison) => void;
  /** KPI figures to render in the expanded body. */
  metrics?: KpiMetric[];
  /** Render a loading state instead of figures. */
  loading?: boolean;
  /** Render an error state instead of figures. */
  error?: string | null;
  /** Whether the panel starts collapsed. Defaults to expanded (Req 30.1). */
  defaultCollapsed?: boolean;
  /** Test seam: the instant used to resolve "today" in the timezone. */
  now?: Date;
  className?: string;
}

function formatRange(range: { start: string; end: string }): string {
  return range.start === range.end
    ? range.start
    : `${range.start} ~ ${range.end}`;
}

function DeltaBadge({
  current,
  prior,
  inverse,
}: {
  current: number;
  prior: number;
  inverse?: boolean;
}) {
  const change = periodOverPeriodChange(current, prior);
  if (change === null) {
    // No honest baseline — show a neutral, non-fabricated marker (Req 19.4).
    return <span className="text-xs text-slate-400">—</span>;
  }
  const isUp = change > 0;
  const isFlat = change === 0;
  const good = inverse ? change < 0 : change > 0;
  const tone = isFlat
    ? 'text-slate-400'
    : good
      ? 'text-emerald-600'
      : 'text-red-500';
  const Icon = isFlat ? Minus : isUp ? TrendingUp : TrendingDown;
  return (
    <span className={cn('inline-flex items-center gap-0.5 text-xs font-medium', tone)}>
      <Icon size={12} />
      {`${isUp ? '+' : ''}${(change * 100).toFixed(1)}%`}
    </span>
  );
}

/**
 * Collapsible KPI + comparison panel. Defaults to last-7-days vs prior-7-days
 * computed in the Marketplace_Timezone.
 */
export function KpiPanel({
  timeZone,
  comparison,
  onComparisonChange,
  metrics = [],
  loading = false,
  error = null,
  defaultCollapsed = false,
  now,
  className,
}: KpiPanelProps) {
  const [open, setOpen] = useState(!defaultCollapsed);

  // Resolve the default comparison in the Marketplace_Timezone unless an
  // explicit one was supplied. A missing/invalid timezone is surfaced as a
  // configuration error rather than defaulting to the server timezone (Req 30.4).
  const { resolved, tzError } = useMemo(() => {
    if (comparison) return { resolved: comparison, tzError: null as string | null };
    try {
      return {
        resolved: resolveDefaultPeriodComparison(timeZone, { now }),
        tzError: null as string | null,
      };
    } catch (err) {
      if (err instanceof MarketplaceTimezoneError) {
        return { resolved: null, tzError: err.message };
      }
      throw err;
    }
  }, [comparison, timeZone, now]);

  // Surface the resolved comparison to the parent (shell wiring, task 20.4).
  // Keyed on the serialized value so a stable comparison doesn't re-notify.
  const resolvedKey = resolved ? JSON.stringify(resolved) : null;
  useEffect(() => {
    if (resolved && onComparisonChange) onComparisonChange(resolved);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resolvedKey]);

  return (
    <Collapsible
      open={open}
      onOpenChange={setOpen}
      data-testid="kpi-panel"
      className={cn(
        'rounded-xl border border-slate-200 bg-white',
        className,
      )}
    >
      <CollapsibleTrigger
        data-testid="kpi-panel-toggle"
        aria-expanded={open}
        aria-label={open ? '收起 KPI 面板' : '展开 KPI 面板'}
        className="flex w-full items-center justify-between gap-3 px-4 py-3 text-left"
      >
        <div className="min-w-0">
          <p className="text-sm font-semibold text-slate-900">关键指标</p>
          {tzError ? (
            <p className="truncate text-xs text-red-500" data-testid="kpi-panel-tz-error">
              {tzError}
            </p>
          ) : resolved ? (
            <p className="truncate text-xs text-slate-500" data-testid="kpi-panel-period">
              {`${formatRange(resolved.current)} · 对比 ${formatRange(resolved.prior)}`}
            </p>
          ) : null}
        </div>
        <ChevronDown
          size={16}
          className={cn(
            'shrink-0 text-slate-400 transition-transform',
            open && 'rotate-180',
          )}
        />
      </CollapsibleTrigger>

      <CollapsibleContent data-testid="kpi-panel-body" className="px-4 pb-4">
        {tzError ? (
          <p className="py-6 text-center text-sm text-red-500">{tzError}</p>
        ) : loading ? (
          <p className="py-6 text-center text-sm text-slate-400">加载中…</p>
        ) : error ? (
          <p className="py-6 text-center text-sm text-red-500">{error}</p>
        ) : metrics.length === 0 ? (
          <p className="py-6 text-center text-sm text-slate-400">暂无指标数据</p>
        ) : (
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
            {metrics.map((metric) => (
              <div
                key={metric.key}
                data-testid={`kpi-metric-${metric.key}`}
                className="rounded-lg border border-slate-200 bg-slate-50/50 px-3 py-2.5"
              >
                <p className="text-xs font-medium text-slate-500">{metric.label}</p>
                <p className="mt-1 text-xl font-bold tabular-nums text-slate-900">
                  {metric.value}
                </p>
                {typeof metric.currentRaw === 'number' &&
                  typeof metric.priorRaw === 'number' ? (
                  <div className="mt-1">
                    <DeltaBadge
                      current={metric.currentRaw}
                      prior={metric.priorRaw}
                      inverse={metric.inverse}
                    />
                  </div>
                ) : null}
              </div>
            ))}
          </div>
        )}
      </CollapsibleContent>
    </Collapsible>
  );
}

export default KpiPanel;
