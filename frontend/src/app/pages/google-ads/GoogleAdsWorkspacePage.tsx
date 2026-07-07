// AdPilot AI — Google 广告 工作台 (single tabbed page)
//
// 做减法：previously the 独立站 block carried SIX separate Google Ads menu items
// / pages (连接 / 列表 / 报告 / 创建 / 调价 / AI托管). They all operate on the same
// store's Google Ads account, so they're folded into ONE page with a tab strip —
// mirroring the 连接与同步 (DataSyncWorkspacePage) pattern. Each existing sub-page
// component is reused unchanged; this workspace only routes between them.
//
// The active tab is taken from the trailing route segment (`/google-ads/:tab`)
// so existing deep links like `/google-ads/connect` (used by GaConnectPrompt)
// still land on the right tab.

import { useState } from 'react';
import { useParams, useNavigate } from 'react-router';
import {
  Megaphone,
  FileBarChart,
  Sparkles,
  Target,
  Brain,
  Link2,
  type LucideIcon,
} from 'lucide-react';
import { cn } from '../../lib/utils';
import { GoogleAdsConnectPage } from './GoogleAdsConnectPage';
import { GoogleAdsCampaignsPage } from './GoogleAdsCampaignsPage';
import { GoogleAdsReportsPage } from './GoogleAdsReportsPage';
import { GoogleAdsCreatePage } from './GoogleAdsCreatePage';
import { GoogleAdsAdjustPage } from './GoogleAdsAdjustPage';
import { GoogleAdsHostingPage } from './GoogleAdsHostingPage';

type GaTab = 'campaigns' | 'reports' | 'create' | 'adjust' | 'hosting' | 'connect';

const TABS: { key: GaTab; label: string; icon: LucideIcon }[] = [
  { key: 'campaigns', label: '广告系列', icon: Megaphone },
  { key: 'reports', label: '报告', icon: FileBarChart },
  { key: 'create', label: '创建', icon: Sparkles },
  { key: 'adjust', label: '调价', icon: Target },
  { key: 'hosting', label: 'AI 托管', icon: Brain },
  { key: 'connect', label: '账号连接', icon: Link2 },
];

const TAB_KEYS = TABS.map((t) => t.key);

function normalizeTab(value?: string): GaTab {
  return (value && (TAB_KEYS as string[]).includes(value) ? value : 'campaigns') as GaTab;
}

export interface GoogleAdsWorkspaceProps {
  /** When embedded inside the per-store cockpit, tab state is local (no route
   *  navigation) and the page chrome is slimmed to just the tab strip. */
  embedded?: boolean;
}

export function GoogleAdsWorkspacePage({ embedded = false }: GoogleAdsWorkspaceProps = {}) {
  const { tab } = useParams();
  const navigate = useNavigate();
  // Standalone: tab is route-driven and deep-linkable. Embedded: tab is local
  // state so switching never leaves the cockpit (the route param is unused).
  const [localTab, setLocalTab] = useState<GaTab>(normalizeTab(tab));
  const activeTab = embedded ? localTab : normalizeTab(tab);

  const setTab = (next: GaTab) => {
    if (embedded) {
      setLocalTab(next);
      return;
    }
    // Keep the URL deep-linkable per tab; campaigns is the canonical base path.
    navigate(next === 'campaigns' ? '/google-ads' : `/google-ads/${next}`);
  };

  return (
    <div className="space-y-4">
      {!embedded && (
        <div>
          <h1 className="text-xl font-semibold text-slate-900">Google 广告</h1>
          <p className="mt-1 text-sm text-slate-500">
            当前独立站店铺的 Google Ads：查看广告系列与报告、创建与调价、AI 托管，以及账号连接。
          </p>
        </div>
      )}

      {/* Tab strip — one page replaces the former six menu items. */}
      <div className="flex flex-wrap gap-2 border-b border-slate-200 pb-3">
        {TABS.map(({ key, label, icon: Icon }) => {
          const selected = key === activeTab;
          return (
            <button
              key={key}
              type="button"
              onClick={() => setTab(key)}
              className={cn(
                'inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm font-medium transition-colors',
                selected
                  ? 'bg-blue-50 text-blue-700 border border-blue-200'
                  : 'border border-transparent text-slate-600 hover:bg-slate-50',
              )}
            >
              <Icon size={15} className={selected ? 'text-blue-600' : 'text-slate-400'} />
              {label}
            </button>
          );
        })}
      </div>

      {/* Active view — existing page components, reused unchanged. */}
      {activeTab === 'campaigns' && <GoogleAdsCampaignsPage />}
      {activeTab === 'reports' && <GoogleAdsReportsPage />}
      {activeTab === 'create' && <GoogleAdsCreatePage />}
      {activeTab === 'adjust' && <GoogleAdsAdjustPage />}
      {activeTab === 'hosting' && <GoogleAdsHostingPage />}
      {activeTab === 'connect' && <GoogleAdsConnectPage />}
    </div>
  );
}

export default GoogleAdsWorkspacePage;
