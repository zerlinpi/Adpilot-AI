import { describe, it, expect } from 'vitest';

import {
  computePersonalityDistribution,
  derivePersonalityPreview,
  formatRatioPercent,
  inheritanceLabel,
  isAiPersonality,
  isOverride,
  resolveCampaignPersonality,
  type PersonalityPolicy,
} from './aiPersonality';

describe('isAiPersonality', () => {
  it('accepts only the three canonical machine values', () => {
    expect(isAiPersonality('conservative')).toBe(true);
    expect(isAiPersonality('balanced')).toBe(true);
    expect(isAiPersonality('aggressive')).toBe(true);
    expect(isAiPersonality('稳健型')).toBe(false);
    expect(isAiPersonality(null)).toBe(false);
    expect(isAiPersonality(undefined)).toBe(false);
    expect(isAiPersonality('')).toBe(false);
  });
});

describe('resolveCampaignPersonality (Req 49.2/49.3, 50.7)', () => {
  it('prefers the Campaign override and marks it as an override', () => {
    const r = resolveCampaignPersonality({
      campaignPersonality: 'aggressive',
      goalPersonality: 'conservative',
      storePersonality: 'balanced',
    });
    expect(r).toEqual({ personality: 'aggressive', source: 'override' });
    expect(isOverride(r.source)).toBe(true);
  });

  it('falls back to the Goal default when no override is set', () => {
    const r = resolveCampaignPersonality({ goalPersonality: 'conservative', storePersonality: 'balanced' });
    expect(r).toEqual({ personality: 'conservative', source: 'goal' });
    expect(isOverride(r.source)).toBe(false);
  });

  it('falls back to the Store default when neither override nor goal is set', () => {
    const r = resolveCampaignPersonality({ storePersonality: 'aggressive' });
    expect(r).toEqual({ personality: 'aggressive', source: 'store' });
  });

  it('falls back to the system default balanced when nothing resolves', () => {
    expect(resolveCampaignPersonality({})).toEqual({ personality: 'balanced', source: 'fallback' });
  });

  it('treats a non-canonical value at any level as unset and falls through', () => {
    const r = resolveCampaignPersonality({ campaignPersonality: '稳健型', goalPersonality: 'conservative' });
    expect(r).toEqual({ personality: 'conservative', source: 'goal' });
  });
});

describe('inheritanceLabel', () => {
  it('returns a label for every source', () => {
    expect(inheritanceLabel('override')).toBe('活动级覆盖');
    expect(inheritanceLabel('goal')).toBe('继承自目标');
    expect(inheritanceLabel('store')).toBe('继承自店铺默认');
    expect(inheritanceLabel('fallback')).toBe('系统默认');
  });
});

describe('formatRatioPercent', () => {
  it('formats decimal ratios as percentages and guards bad input', () => {
    expect(formatRatioPercent(0.1)).toBe('10%');
    expect(formatRatioPercent(0.075, 1)).toBe('7.5%');
    expect(formatRatioPercent(null)).toBe('—');
    expect(formatRatioPercent(undefined)).toBe('—');
  });
});

describe('derivePersonalityPreview (Req 50.4/50.5)', () => {
  it('always returns the five facets in a stable order', () => {
    const items = derivePersonalityPreview('balanced');
    expect(items.map((i) => i.key)).toEqual(['coreGoal', 'frequency', 'magnitude', 'keyword', 'approval']);
  });

  it('shows a pending note when no policy is supplied (never fabricates a limit)', () => {
    const items = derivePersonalityPreview('aggressive');
    const magnitude = items.find((i) => i.key === 'magnitude')!;
    expect(magnitude.pending).toBe(true);
  });

  it('derives concrete figures from the backend policy', () => {
    const policy: PersonalityPolicy = {
      personality: 'balanced',
      adjustmentCooldownHours: 12,
      maxBidIncreaseRatio: 0.1,
      maxDailyBudgetIncreaseRatio: 0.15,
      approvalBidChangeRatio: 0.07,
      approvalBudgetChangeRatio: 0.1,
      keywordExpansionMode: 'suggest',
      negativeKeywordMode: 'approval',
    };
    const items = derivePersonalityPreview('balanced', policy);
    const byKey = Object.fromEntries(items.map((i) => [i.key, i]));
    expect(byKey.frequency.value).toContain('12');
    expect(byKey.frequency.pending).toBeFalsy();
    expect(byKey.magnitude.value).toContain('10%');
    expect(byKey.magnitude.value).toContain('15%');
    expect(byKey.approval.value).toContain('7%');
    expect(byKey.keyword.value).toContain('仅建议加词');
  });
});

describe('computePersonalityDistribution (Req 50.11)', () => {
  it('counts per personality in canonical order and ignores non-canonical values', () => {
    const dist = computePersonalityDistribution([
      'balanced',
      'balanced',
      'aggressive',
      'conservative',
      '稳健型',
    ]);
    expect(dist.map((d) => d.personality)).toEqual(['conservative', 'balanced', 'aggressive']);
    expect(dist.map((d) => d.count)).toEqual([1, 2, 1]);
    expect(dist[1].display).toBe('平衡型');
  });
});
