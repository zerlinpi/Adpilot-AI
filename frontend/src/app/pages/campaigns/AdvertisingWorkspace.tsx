import { useMemo, useState, type ReactNode } from 'react';
import { cn } from '../../lib/utils';

import { CAMPAIGNS_TABS, type CampaignsTabKey } from './CampaignsWorkspace';
import {
  ADVERTISING_GROUPS,
  groupForTab,
  tabsForGroup,
  type AdvertisingGroupKey,
} from './advertisingGroups';
import { useResponsiveMode } from '../../lib/hooks/useResponsiveMode';
import { type ResponsiveMode } from '../../lib/advertisingResponsive';

// Re-export the partition surface so consumers can import the shell and its
// taxonomy from one module.
export {
  ADVERTISING_GROUPS,
  groupForTab,
  tabsForGroup,
  type AdvertisingGroupKey,
  type AdvertisingGroupDef,
} from './advertisingGroups';

/** Quick lookup of a tab's display label from the canonical taxonomy. */
const TAB_LABEL: Readonly<Record<CampaignsTabKey, string>> = Object.fromEntries(
  CAMPAIGNS_TABS.map((t) => [t.key, t.label]),
) as Record<CampaignsTabKey, string>;

/**
 * The highest-frequency Amazon Ads entity path. These are not new routes or
 * views; each step activates an existing Campaigns_Workspace tab. Keeping the
 * path visible above the broader feature taxonomy gives operators a predictable
 * Campaign → Ad Group → Targeting → Search Term workflow without first having
 * to decide which feature group owns the next object.
 */
const OPERATOR_FLOW: readonly CampaignsTabKey[] = [
  'campaigns',
  'adGroups',
  'targeting',
  'searchTerms',
] as const;

export interface AdvertisingWorkspaceProps {
  /**
   * Renders the content for the currently selected tab. The workspace calls this
   * only for the active tab so that exactly one tab's content is mounted at a
   * time. Per-tab content is supplied by the host page.
   */
  renderTab: (key: CampaignsTabKey) => ReactNode;
  /** Optional content rendered above the group/tab navigation (e.g. the page header). */
  header?: ReactNode;
  /**
   * Optional Context_Bar rendered in the header area, below the page header and
   * above the navigation (Req 29.1). Supplied by the host page because the
   * context values come from the active-store context.
   */
  contextBar?: ReactNode;
  /**
   * Optional applied-filter chips rendered above the tab content, between the
   * navigation and the table (Req 29.2, 29.3). Supplied by the host page so the
   * chips read the page's single-source-of-truth query state.
   */
  filterChips?: ReactNode;
  /**
   * Optional collapsible KPI_Panel rendered in the header area, below the
   * Context_Bar and above the navigation (Req 30.1). Integrated into the shell
   * layout here (task 20.4); the host page wires it to the query state and the
   * data layer.
   */
  kpiPanel?: ReactNode;
  /**
   * Optional read-oriented alternative renderer used below the responsive
   * threshold (Req 43.2). When the viewport is narrower than 768px and this is
   * provided, the shell renders this instead of {@link renderTab} so small
   * screens get a stacked, view/pause/approve-only view rather than the full
   * desktop table. When omitted, the full content is rendered at every width.
   */
  renderCompact?: (key: CampaignsTabKey) => ReactNode;
  /**
   * Test/host seam to force the responsive mode. When omitted the shell derives
   * the mode from the live viewport width via {@link useResponsiveMode}, the
   * 768px threshold (Req 43.3) deciding desktop vs compact.
   */
  responsiveMode?: ResponsiveMode;
  /** Tab selected on first render. Defaults to the first tab of the first group. */
  initialTab?: CampaignsTabKey;
  /** Controlled active tab. When provided, the parent owns selection state. */
  activeTab?: CampaignsTabKey;
  /** Notified whenever the operator selects a tab (via a group or tab click). */
  onTabChange?: (key: CampaignsTabKey) => void;
}

/**
 * AdvertisingWorkspace — the four-group advertising navigation shell (Req 28).
 *
 * Presents the four navigation groups (广告管理 / 搜索词管理 / 智能优化 /
 * 记录与审计) as a presentation layer OVER the eleven existing
 * Campaigns_Workspace tabs. The tab→group assignment is a total partition
 * (every existing tab belongs to exactly one group, no tab removed — Req 28.1,
 * 28.2), sourced from the pure {@link ADVERTISING_GROUPS} mapping.
 *
 * Navigation is two-level: selecting a group reveals that group's tabs
 * (Req 28.3) and activates the group's first tab; the active tab's content is
 * rendered through {@link AdvertisingWorkspaceProps.renderTab}. Tab labels are
 * the same canonical labels used everywhere, so a given state reads identically
 * in every group (Req 28.4).
 *
 * This is the BASE shell: it owns only the four-group navigation partition.
 * Context bar, filter chips, KPI panel, context preservation and responsive
 * behavior are layered on by later tasks.
 */
export function AdvertisingWorkspace({
  renderTab,
  header,
  contextBar,
  filterChips,
  kpiPanel,
  renderCompact,
  responsiveMode,
  initialTab,
  activeTab: controlledActiveTab,
  onTabChange,
}: AdvertisingWorkspaceProps) {
  const defaultTab = initialTab ?? ADVERTISING_GROUPS[0].tabs[0];

  const [uncontrolledActiveTab, setUncontrolledActiveTab] =
    useState<CampaignsTabKey>(defaultTab);

  const isControlled = controlledActiveTab !== undefined;
  const activeTab = isControlled ? controlledActiveTab : uncontrolledActiveTab;

  // Responsive decision (Req 43.2–43.4): below 768px we render the read-oriented
  // alternative when the host supplies one; at/above 768px the full desktop
  // table renders. An explicit prop overrides the live viewport measurement.
  const liveMode = useResponsiveMode();
  const mode = responsiveMode ?? liveMode;
  const isCompact = mode === 'compact';

  // The active group is derived from the active tab: the group that owns the
  // currently selected tab is the one shown as selected (Req 28.3). This keeps a
  // single source of truth (the active tab) and guarantees the group nav stays
  // consistent with the visible tab.
  const activeGroup: AdvertisingGroupKey = useMemo(
    () => groupForTab(activeTab) ?? ADVERTISING_GROUPS[0].key,
    [activeTab],
  );

  const groupTabs = useMemo(() => tabsForGroup(activeGroup), [activeGroup]);

  const selectTab = (key: CampaignsTabKey) => {
    if (!isControlled) {
      setUncontrolledActiveTab(key);
    }
    onTabChange?.(key);
  };

  // Selecting a group activates that group's first tab (Req 28.3).
  const selectGroup = (group: AdvertisingGroupKey) => {
    if (group === activeGroup) return;
    const firstTab = tabsForGroup(group)[0];
    if (firstTab) selectTab(firstTab);
  };

  return (
    <div className="space-y-4">
      {header}

      {/* Context bar — persistent data-context header (Req 29.1) */}
      {contextBar}

      {/* Collapsible KPI panel — header area, above the navigation (Req 30.1) */}
      {kpiPanel}

      {/*
        Operator flow — a stable entity path independent of the broader feature
        grouping below. It deliberately reuses the existing tabs so state,
        permissions and data loading remain unchanged.
      */}
      <nav
        aria-label="广告对象操作链路"
        className="overflow-x-auto rounded-lg border border-slate-200 bg-white"
      >
        <div className="flex min-w-max items-center px-2 py-2">
          <span className="px-2 text-[11px] font-semibold uppercase tracking-wide text-slate-400">
            操作链路
          </span>
          {OPERATOR_FLOW.map((tabKey, index) => {
            const selected = activeTab === tabKey;
            return (
              <div key={tabKey} className="flex items-center">
                {index > 0 && (
                  <span aria-hidden="true" className="px-1 text-xs text-slate-300">
                    →
                  </span>
                )}
                <button
                  type="button"
                  aria-current={selected ? 'step' : undefined}
                  onClick={() => selectTab(tabKey)}
                  className={cn(
                    'inline-flex items-center gap-2 rounded-md px-2.5 py-1.5 text-xs font-medium transition-colors',
                    selected
                      ? 'bg-blue-50 text-blue-700 ring-1 ring-inset ring-blue-200'
                      : 'text-slate-600 hover:bg-slate-50 hover:text-slate-900',
                  )}
                >
                  <span
                    className={cn(
                      'flex h-5 w-5 items-center justify-center rounded-full text-[10px] font-semibold',
                      selected ? 'bg-blue-600 text-white' : 'bg-slate-100 text-slate-500',
                    )}
                  >
                    {index + 1}
                  </span>
                  {TAB_LABEL[tabKey]}
                </button>
              </div>
            );
          })}
        </div>
      </nav>

      {/* Group navigation — exactly the four groups (Req 28.1) */}
      <div
        className="flex items-center gap-2 flex-wrap"
        role="tablist"
        aria-label="广告功能分组"
      >
        {ADVERTISING_GROUPS.map((group) => {
          const selected = activeGroup === group.key;
          return (
            <button
              key={group.key}
              type="button"
              role="tab"
              aria-selected={selected}
              onClick={() => selectGroup(group.key)}
              className={cn(
                'px-4 py-2 text-sm font-medium rounded-lg transition-colors',
                selected
                  ? 'bg-blue-600 text-white shadow-sm'
                  : 'bg-slate-100 text-slate-600 hover:bg-slate-200',
              )}
            >
              {group.label}
            </button>
          );
        })}
      </div>

      {/* Tab navigation — only the active group's tabs are shown (Req 28.3) */}
      <div className="border-b border-slate-200 overflow-x-auto" role="tablist" aria-label="广告页签">
        <div className="flex items-center gap-1 min-w-max">
          {groupTabs.map((tabKey) => {
            const selected = activeTab === tabKey;
            return (
              <button
                key={tabKey}
                type="button"
                role="tab"
                aria-selected={selected}
                onClick={() => selectTab(tabKey)}
                className={cn(
                  'px-3.5 py-2.5 text-sm font-medium border-b-2 transition-colors whitespace-nowrap',
                  selected
                    ? 'border-blue-600 text-blue-600'
                    : 'border-transparent text-slate-500 hover:text-slate-700',
                )}
              >
                {TAB_LABEL[tabKey]}
              </button>
            );
          })}
        </div>
      </div>

      {/* Applied-filter chips — above the table content (Req 29.2, 29.3) */}
      {filterChips}

      {/*
        Tab content — only the selected tab's content is rendered. Below the
        768px threshold (Req 43.2) the read-oriented alternative is rendered when
        the host supplies one; otherwise the full desktop table renders (Req
        43.3). The mode switches as the threshold is crossed (Req 43.4).
      */}
      <div role="tabpanel" data-responsive-mode={mode}>
        {isCompact && renderCompact
          ? renderCompact(activeTab)
          : renderTab(activeTab)}
      </div>
    </div>
  );
}

export default AdvertisingWorkspace;
