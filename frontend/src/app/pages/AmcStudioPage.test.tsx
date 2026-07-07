// Unit tests for the AMC data studio activation gating.
// Validates: Requirements 30.7

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';

import { AmcStudioPage } from './AmcStudioPage';
import type { AmcTemplates } from '../lib/api';

vi.mock('../lib/api', () => ({
  fetchAmcModels: vi.fn(),
  fetchAmcAudiences: vi.fn(),
}));

vi.mock('../lib/useStoreId', () => ({
  useStoreId: vi.fn(),
}));

import { fetchAmcModels, fetchAmcAudiences } from '../lib/api';
import { useStoreId } from '../lib/useStoreId';

const STORE_ID = 'store-1';

describe('AmcStudioPage activation gating (Req 30.7)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(useStoreId).mockReturnValue({ storeId: STORE_ID, loading: false, error: null });
  });

  it('shows the activation banner and disables a template that requires activation', async () => {
    const models: AmcTemplates = {
      activated: false,
      message: 'AMC 功能需先激活亚马逊营销云实例后方可运行。',
      templates: [
        { key: 'm1', name: '受众重叠分析', category: '受众', activationRequired: true },
      ],
    };
    vi.mocked(fetchAmcModels).mockResolvedValue(models);

    render(<AmcStudioPage />);

    // Activation gating banner is rendered (Req 30.7).
    expect(await screen.findByText('该功能需要激活')).toBeInTheDocument();
    expect(
      screen.getByText('AMC 功能需先激活亚马逊营销云实例后方可运行。'),
    ).toBeInTheDocument();

    // The template card surfaces a 需激活 badge and a disabled run button.
    expect(screen.getByText('需激活')).toBeInTheDocument();
    const runButton = screen.getByRole('button', { name: '运行模型' });
    expect(runButton).toBeDisabled();
  });

  it('does not show the activation banner when AMC is activated', async () => {
    const models: AmcTemplates = {
      activated: true,
      templates: [
        { key: 'm1', name: '搜索词分析', category: '搜索', activationRequired: false },
      ],
    };
    vi.mocked(fetchAmcModels).mockResolvedValue(models);

    render(<AmcStudioPage />);

    expect(await screen.findByText('搜索词分析')).toBeInTheDocument();
    expect(screen.queryByText('该功能需要激活')).not.toBeInTheDocument();
    expect(screen.queryByText('需激活')).not.toBeInTheDocument();
    // An activated template's run action is enabled.
    expect(screen.getByRole('button', { name: '运行模型' })).toBeEnabled();
  });

  it('renders an empty-state when an activated workspace exposes no templates', async () => {
    const models: AmcTemplates = { activated: true, templates: [] };
    vi.mocked(fetchAmcModels).mockResolvedValue(models);

    render(<AmcStudioPage />);

    expect(await screen.findByText('暂无可用模板')).toBeInTheDocument();
  });

  it('loads the audiences workspace lazily only when its tab is selected', async () => {
    vi.mocked(fetchAmcModels).mockResolvedValue({ activated: true, templates: [] });
    vi.mocked(fetchAmcAudiences).mockResolvedValue({ activated: true, templates: [] });

    render(<AmcStudioPage />);

    // The models tab loads on mount; audiences stays untouched until selected.
    await waitFor(() => expect(fetchAmcModels).toHaveBeenCalledWith(STORE_ID));
    expect(fetchAmcAudiences).not.toHaveBeenCalled();
  });
});
