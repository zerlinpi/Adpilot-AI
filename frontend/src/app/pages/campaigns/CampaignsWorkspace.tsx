import { useState, type ReactNode } from 'react';
import { cn } from '../../lib/utils';

// ─── Tab taxonomy (Req 1.1 / app-functionality-completion Req 19.1) ───────────
// The eleven Campaigns_Workspace tabs in their canonical order. This taxonomy is
// owned by app-functionality-completion Req 19 and is reproduced here verbatim.
export type CampaignsTabKey =
  | 'campaigns'
  | 'hosting'
  | 'adGroups'
  | 'promotedProducts'
  | 'targeting'
  | 'negativeTargeting'
  | 'searchTerms'
  | 'otherProducts'
  | 'bidAdjustments'
  | 'budgetCaps'
  | 'operationLog';

export interface CampaignsTabDef {
  key: CampaignsTabKey;
  label: string;
}

/**
 * The exactly-eleven Campaigns_Workspace tabs, in order (Req 1.1):
 * 广告活动, AI托管, 广告组, 推广商品, 投放, 否定投放, 搜索词,
 * 购买的其他商品, 竞价调整, SP预算上限, 操作日志.
 */
export const CAMPAIGNS_TABS: readonly CampaignsTabDef[] = [
  { key: 'campaigns', label: '广告活动' },
  { key: 'hosting', label: 'AI托管' },
  { key: 'adGroups', label: '广告组' },
  { key: 'promotedProducts', label: '推广商品' },
  { key: 'targeting', label: '投放' },
  { key: 'negativeTargeting', label: '否定投放' },
  { key: 'searchTerms', label: '搜索词' },
  { key: 'otherProducts', label: '购买的其他商品' },
  { key: 'bidAdjustments', label: '竞价调整' },
  { key: 'budgetCaps', label: 'SP预算上限' },
  { key: 'operationLog', label: '操作日志' },
] as const;

export interface CampaignsWorkspaceProps {
  /**
   * Renders the content for the currently selected tab. The workspace calls this
   * only for the active tab so that exactly one tab's content is mounted at a time
   * (Req 1.2). Per-tab content is supplied by the host page (extraction is task 22.2).
   */
  renderTab: (key: CampaignsTabKey) => ReactNode;
  /** Optional content rendered above the tab strip (e.g. the page header). */
  header?: ReactNode;
  /** Tab selected on first render. Defaults to the first tab (广告活动). */
  initialTab?: CampaignsTabKey;
  /** Controlled active tab. When provided, the parent owns selection state. */
  activeTab?: CampaignsTabKey;
  /** Notified whenever the operator selects a tab. */
  onTabChange?: (key: CampaignsTabKey) => void;
}

/**
 * CampaignsWorkspace — the advertising workspace tab container (Req 1.1, 1.2).
 *
 * Renders exactly the eleven tabs in their canonical order and displays only the
 * selected tab's content. It is a standalone, importable module; the concrete
 * per-tab views are provided through {@link CampaignsWorkspaceProps.renderTab}.
 */
export function CampaignsWorkspace({
  renderTab,
  header,
  initialTab,
  activeTab: controlledActiveTab,
  onTabChange,
}: CampaignsWorkspaceProps) {
  const [uncontrolledActiveTab, setUncontrolledActiveTab] = useState<CampaignsTabKey>(
    initialTab ?? CAMPAIGNS_TABS[0].key,
  );

  const isControlled = controlledActiveTab !== undefined;
  const activeTab = isControlled ? controlledActiveTab : uncontrolledActiveTab;

  const selectTab = (key: CampaignsTabKey) => {
    if (!isControlled) {
      setUncontrolledActiveTab(key);
    }
    onTabChange?.(key);
  };

  return (
    <div className="space-y-5">
      {header}

      {/* Tab navigation — exactly eleven tabs in order (Req 1.1) */}
      <div className="border-b border-slate-200 overflow-x-auto" role="tablist">
        <div className="flex items-center gap-1 min-w-max">
          {CAMPAIGNS_TABS.map((tab) => {
            const selected = activeTab === tab.key;
            return (
              <button
                key={tab.key}
                type="button"
                role="tab"
                aria-selected={selected}
                onClick={() => selectTab(tab.key)}
                className={cn(
                  'px-3.5 py-2.5 text-sm font-medium border-b-2 transition-colors whitespace-nowrap',
                  selected
                    ? 'border-blue-600 text-blue-600'
                    : 'border-transparent text-slate-500 hover:text-slate-700',
                )}
              >
                {tab.label}
              </button>
            );
          })}
        </div>
      </div>

      {/* Tab content — only the selected tab's content is rendered (Req 1.2) */}
      <div role="tabpanel">{renderTab(activeTab)}</div>
    </div>
  );
}
