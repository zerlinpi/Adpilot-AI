// Tests for the persisted-config mode of <HostingSettingsDrawer> (Req 12.1, 12.2,
// 12.5, 12.6). When a `storeId` is supplied the drawer loads the store's hosting
// configuration on open, persists edits on submit, surfaces backend validation
// errors inline, and signals inherited (null) values.
//
// Validates: Requirements 12.1, 12.2, 12.5, 12.6

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

import { HostingSettingsDrawer } from './HostingSettingsDrawer';
import { fetchHostingCanary, fetchHostingConfig, saveHostingConfig, type HostingConfig } from '../../lib/api';

// Isolate the drawer from the real network layer.
vi.mock('../../lib/api', () => ({
  addHostingCanaryStore: vi.fn(),
  disableHostingCanary: vi.fn(),
  enableHostingCanary: vi.fn(),
  fetchHostingCanary: vi.fn(),
  fetchHostingConfig: vi.fn(),
  removeHostingCanaryStore: vi.fn(),
  saveHostingConfig: vi.fn(),
}));

// jsdom does not implement matchMedia, which useIsMobile relies on.
beforeEach(() => {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: () => { },
      removeEventListener: () => { },
      addListener: () => { },
      removeListener: () => { },
      dispatchEvent: () => false,
    }),
  });
});

const STORE_ID = 'store-1';

function makeConfig(overrides: Partial<HostingConfig> = {}): HostingConfig {
  return {
    scope: 'store',
    scope_id: STORE_ID,
    store_id: STORE_ID,
    active_phase: 'V1',
    default_personality: 'balanced',
    execution_mode: 'approval_required',
    auto_execute_threshold: 0.3,
    emergency_auto_action_enabled: false,
    shadow_mode: false,
    notification_preferences: null,
    boundary_overrides: { MAX_BID: 2.5 },
    ...overrides,
  };
}

describe('<HostingSettingsDrawer> persisted mode', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(fetchHostingConfig).mockResolvedValue(makeConfig());
    vi.mocked(fetchHostingCanary).mockResolvedValue({
      org_id: 'org-1',
      enabled: false,
      store_ids: [],
    });
    vi.mocked(saveHostingConfig).mockResolvedValue(makeConfig());
  });

  it('loads the current store config on open (Req 12.1)', async () => {
    render(<HostingSettingsDrawer open storeId={STORE_ID} onClose={() => { }} />);

    await waitFor(() => expect(fetchHostingConfig).toHaveBeenCalledWith(STORE_ID));
    // Threshold field is populated from the loaded config.
    expect(await screen.findByLabelText('自动执行阈值')).toHaveValue(0.3);
    // Loaded boundary override is reflected in its field.
    expect(screen.getByLabelText('最高竞价')).toHaveValue(2.5);
  });

  it('persists edited settings via PUT on save (Req 12.2)', async () => {
    const onSaved = vi.fn();
    render(<HostingSettingsDrawer open storeId={STORE_ID} onClose={() => { }} onSaved={onSaved} />);

    const threshold = await screen.findByLabelText('自动执行阈值');
    fireEvent.change(threshold, { target: { value: '0.75' } });
    fireEvent.click(screen.getByText('自动执行'));
    fireEvent.click(screen.getByText('保存设置'));

    await waitFor(() => expect(saveHostingConfig).toHaveBeenCalledTimes(1));
    const [storeArg, body] = vi.mocked(saveHostingConfig).mock.calls[0];
    expect(storeArg).toBe(STORE_ID);
    expect(body).toMatchObject({
      auto_execute_threshold: 0.75,
      execution_mode: 'auto_execute',
      default_personality: 'balanced',
      boundary_overrides: { MAX_BID: 2.5 },
    });
    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
  });

  it('surfaces backend validation errors inline without closing (Req 12.6)', async () => {
    vi.mocked(saveHostingConfig).mockRejectedValue(
      new Error('auto_execute_threshold must be within 0.0–1.0'),
    );
    const onClose = vi.fn();
    render(<HostingSettingsDrawer open storeId={STORE_ID} onClose={onClose} />);

    await screen.findByLabelText('自动执行阈值');
    fireEvent.click(screen.getByText('保存设置'));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'auto_execute_threshold must be within 0.0–1.0',
    );
    expect(onClose).not.toHaveBeenCalled();
  });

  it('marks inherited (null) settings with a hint (Req 12.5)', async () => {
    vi.mocked(fetchHostingConfig).mockResolvedValue(
      makeConfig({ execution_mode: null, auto_execute_threshold: null }),
    );
    render(<HostingSettingsDrawer open storeId={STORE_ID} onClose={() => { }} />);

    await screen.findByLabelText('自动执行阈值');
    // Two inherited fields → two hints.
    expect(screen.getAllByText('继承自上级默认').length).toBeGreaterThanOrEqual(2);
  });

  it('surfaces a load failure inline (Req 12.1)', async () => {
    vi.mocked(fetchHostingConfig).mockRejectedValue(new Error('HOSTING config load failed'));
    render(<HostingSettingsDrawer open storeId={STORE_ID} onClose={() => { }} />);

    expect(await screen.findByText('HOSTING config load failed')).toBeInTheDocument();
  });
});
