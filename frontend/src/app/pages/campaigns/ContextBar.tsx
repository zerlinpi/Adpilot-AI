// ContextBar — the persistent advertising context header (Req 29.1).
//
// Shows the active store, site (marketplace), advertising account, currency,
// data date, and last-sync time so the operator always knows the data context
// the workspace is showing. This is a presentational component: the host page
// (CampaignsWorkspacePage) sources the values from the active-store context and
// passes them in. Absent values render a neutral placeholder rather than an
// empty gap, so the bar layout stays stable.

import {
  Store,
  Globe,
  CircleUser,
  Coins,
  CalendarDays,
  RefreshCw,
} from 'lucide-react';

import { cn } from '../../lib/utils';

export interface ContextBarProps {
  /** Active store display name. */
  store?: string | null;
  /** Site / marketplace (e.g. 美国 / US). */
  site?: string | null;
  /** Advertising account label. */
  account?: string | null;
  /** Currency code of the active store (e.g. USD). */
  currency?: string | null;
  /** Date of the data currently shown (e.g. 2024-06-01). */
  dataDate?: string | null;
  /** Last successful sync timestamp, preformatted for display. */
  lastSync?: string | null;
  /** Extra class names for the bar root. */
  className?: string;
}

/** The neutral placeholder shown when a context value is unavailable. */
const EMPTY = '—';

interface ContextItemProps {
  icon: React.ReactNode;
  label: string;
  value?: string | null;
}

function ContextItem({ icon, label, value }: ContextItemProps) {
  return (
    <div className="flex items-center gap-1.5" aria-label={label} title={label}>
      <span className="text-slate-400" aria-hidden>
        {icon}
      </span>
      <span className="text-slate-400">{label}</span>
      <span className="font-medium text-slate-700">{value || EMPTY}</span>
    </div>
  );
}

/**
 * ContextBar — renders the six context facets defined by Req 29.1 in a single
 * persistent header row.
 */
export function ContextBar({
  store,
  site,
  account,
  currency,
  dataDate,
  lastSync,
  className,
}: ContextBarProps) {
  return (
    <div
      role="region"
      aria-label="数据上下文"
      className={cn(
        'flex flex-wrap items-center gap-x-5 gap-y-2 rounded-lg border border-slate-200 bg-slate-50 px-4 py-2.5 text-sm',
        className,
      )}
    >
      <ContextItem icon={<Store size={15} />} label="店铺" value={store} />
      <ContextItem icon={<Globe size={15} />} label="站点" value={site} />
      <ContextItem icon={<CircleUser size={15} />} label="广告账户" value={account} />
      <ContextItem icon={<Coins size={15} />} label="币种" value={currency} />
      <ContextItem icon={<CalendarDays size={15} />} label="数据日期" value={dataDate} />
      <ContextItem icon={<RefreshCw size={15} />} label="最近同步" value={lastSync} />
    </div>
  );
}

export default ContextBar;
