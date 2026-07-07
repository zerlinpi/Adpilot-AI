// Four-group advertising information architecture (Req 28.1, 28.2, 28.4).
//
// This module is the PURE, side-effect-free source of truth for how the eleven
// existing Campaigns_Workspace tabs (owned by app-functionality-completion and
// declared in `CampaignsWorkspace`) are regrouped into the four navigational
// groups required by Requirement 28. It contains no React so it can be
// property-tested directly (task 20.5) and reused by the `AdvertisingWorkspace`
// presentation shell.
//
// Requirement 28 is a PRESENTATION regroup only: every existing tab data
// surface is preserved and assigned to exactly one group (a total partition);
// no tab is removed or renamed (Req 28.2).

import { CAMPAIGNS_TABS, type CampaignsTabKey } from './CampaignsWorkspace';

// ─── Group taxonomy (Req 28.1) ───────────────────────────────────────────────

/** Stable machine keys for the four advertising navigation groups. */
export type AdvertisingGroupKey =
  | 'adManagement' // 广告管理
  | 'searchTerm' // 搜索词管理
  | 'optimization' // 智能优化
  | 'audit'; // 记录与审计

export interface AdvertisingGroupDef {
  /** Stable machine key for the group. */
  key: AdvertisingGroupKey;
  /** Display label for the group (Req 28.1). */
  label: string;
  /**
   * The Campaigns_Workspace tabs assigned to this group, in display order.
   * Together the four groups' tab lists form a total partition of
   * {@link CAMPAIGNS_TABS} (Req 28.2).
   */
  tabs: readonly CampaignsTabKey[];
}

/**
 * The four advertising navigation groups (Req 28.1), each mapping to the exact
 * subset of the eleven existing tabs it presents:
 *
 *   - 广告管理   : 广告活动, 广告组, 推广商品, 投放对象
 *   - 搜索词管理 : 搜索词, 否定投放, 购买的其他商品
 *   - 智能优化   : AI托管, 竞价调整, SP预算上限  (竞价与预算 含竞价调整 + SP预算上限)
 *   - 记录与审计 : 操作日志
 *
 * This is a regroup of the EXISTING eleven tabs only; the 优化建议 / 同步记录 /
 * 失败任务 surfaces referenced by Req 28.1 are separate routed pages, not
 * Campaigns_Workspace tabs, so they are not part of this in-workspace partition
 * (task 20.1 scope: "over the eleven existing tabs", no tab removed).
 */
export const ADVERTISING_GROUPS: readonly AdvertisingGroupDef[] = [
  {
    key: 'adManagement',
    label: '广告管理',
    tabs: ['campaigns', 'adGroups', 'promotedProducts', 'targeting'],
  },
  {
    key: 'searchTerm',
    label: '搜索词管理',
    tabs: ['searchTerms', 'negativeTargeting', 'otherProducts'],
  },
  {
    key: 'optimization',
    label: '智能优化',
    tabs: ['hosting', 'bidAdjustments', 'budgetCaps'],
  },
  {
    key: 'audit',
    label: '记录与审计',
    tabs: ['operationLog'],
  },
] as const;

// ─── Pure partition accessors ────────────────────────────────────────────────

/**
 * The reverse index: every {@link CampaignsTabKey} mapped to the single group it
 * belongs to. Built once from {@link ADVERTISING_GROUPS}. Because the groups are
 * a total partition, every tab key resolves to exactly one group.
 */
const TAB_TO_GROUP: Readonly<Record<CampaignsTabKey, AdvertisingGroupKey>> = (() => {
  const map = {} as Record<CampaignsTabKey, AdvertisingGroupKey>;
  for (const group of ADVERTISING_GROUPS) {
    for (const tab of group.tabs) {
      map[tab] = group.key;
    }
  }
  return map;
})();

/**
 * Returns the group a tab belongs to, or `undefined` if the tab is not assigned
 * to any group. For a valid total partition every {@link CampaignsTabKey}
 * resolves to a defined group.
 */
export function groupForTab(tab: CampaignsTabKey): AdvertisingGroupKey | undefined {
  return Object.prototype.hasOwnProperty.call(TAB_TO_GROUP, tab)
    ? TAB_TO_GROUP[tab]
    : undefined;
}

/** Returns the tabs assigned to a group, in display order. */
export function tabsForGroup(group: AdvertisingGroupKey): readonly CampaignsTabKey[] {
  return ADVERTISING_GROUPS.find((g) => g.key === group)?.tabs ?? [];
}

/** Returns the group definition for a group key, if any. */
export function groupDef(group: AdvertisingGroupKey): AdvertisingGroupDef | undefined {
  return ADVERTISING_GROUPS.find((g) => g.key === group);
}

/**
 * Verifies the tab→group assignment is a TOTAL PARTITION of the eleven existing
 * tabs (Req 28.2): every tab in {@link CAMPAIGNS_TABS} is assigned to exactly one
 * group, no tab is assigned to more than one group, and no group references a
 * tab outside {@link CAMPAIGNS_TABS}. Exposed as a pure predicate so it can be
 * asserted in tests without React.
 */
export function isTotalPartition(): boolean {
  const allTabKeys = CAMPAIGNS_TABS.map((t) => t.key);
  const assigned = ADVERTISING_GROUPS.flatMap((g) => g.tabs);

  // No duplicates across groups.
  if (new Set(assigned).size !== assigned.length) return false;
  // Same cardinality as the canonical tab set.
  if (assigned.length !== allTabKeys.length) return false;
  // Every assigned tab is a real tab, and every real tab is assigned.
  const canonical = new Set<CampaignsTabKey>(allTabKeys);
  for (const tab of assigned) {
    if (!canonical.has(tab)) return false;
  }
  for (const tab of allTabKeys) {
    if (!assigned.includes(tab)) return false;
  }
  return true;
}
