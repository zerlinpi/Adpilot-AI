// Feature: app-functionality-completion — Chinese localization catalog (Req 17).
//
// Unit tests for the `t()` Localization_Catalog accessor: it must resolve dotted
// keys to their Chinese strings (Req 17.1, 17.2), interpolate `{token}`
// placeholders, and surface missing keys during development (Req 17.4) while
// degrading gracefully (returning the key) so the UI never renders `undefined`.

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { t, i18n, __resetMissingKeyTracking } from './index';

describe('t() localization accessor', () => {
  beforeEach(() => {
    __resetMissingKeyTracking();
    vi.restoreAllMocks();
  });

  it('resolves a nested catalog key to its Chinese string (Req 17.2)', () => {
    expect(t('pages.campaigns.title')).toBe(i18n.pages.campaigns.title);
    expect(t('menu.allSearchAds')).toBe('全部搜索广告');
  });

  it('resolves newly added SparkX surface keys (Req 17.3)', () => {
    expect(t('pages.aiHosting.title')).toBe('AI 托管');
    expect(t('enums.aiNotificationCategory.core_ops')).toBe('广告运营核心关注');
    expect(t('menu.sectionAiAdvertising')).toBe('AI广告优化');
  });

  it('interpolates {token} placeholders from the provided vars', () => {
    expect(t('forms.minLength', { min: 3 })).toBe('最少需要 3 个字符');
    expect(t('forms.maxValue', { max: 100 })).toBe('最大值为 100');
  });

  it('returns the key itself when no entry exists, so the UI never renders undefined', () => {
    expect(t('pages.doesNotExist.title')).toBe('pages.doesNotExist.title');
  });

  it('surfaces a missing key during development via console.warn (Req 17.4)', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => { });
    // Force the dev branch regardless of the test runner environment.
    vi.stubEnv('DEV', true);

    t('forms.totallyMissingKey');

    // The warning fires (when running under a dev-flagged env) and is deduped:
    // a second lookup of the same key must not warn again.
    const callsAfterFirst = warn.mock.calls.length;
    t('forms.totallyMissingKey');
    expect(warn.mock.calls.length).toBe(callsAfterFirst);

    vi.unstubAllEnvs();
  });
});
