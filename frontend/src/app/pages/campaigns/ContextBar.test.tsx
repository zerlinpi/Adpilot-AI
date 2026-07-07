// Example tests for the ContextBar (Task 20.2; Req 29.1).

import { describe, it, expect, afterEach } from 'vitest';
import { render, screen, cleanup, within } from '@testing-library/react';

import { ContextBar } from './ContextBar';

afterEach(cleanup);

describe('ContextBar — data-context header (Req 29.1)', () => {
  it('shows store, site, account, currency, data date, and last sync', () => {
    render(
      <ContextBar
        store="My Store"
        site="美国"
        account="ADV-001"
        currency="USD"
        dataDate="2024-06-01"
        lastSync="2024-06-01 08:00"
      />,
    );

    const region = screen.getByRole('region', { name: '数据上下文' });
    expect(within(region).getByText('My Store')).toBeInTheDocument();
    expect(within(region).getByText('美国')).toBeInTheDocument();
    expect(within(region).getByText('ADV-001')).toBeInTheDocument();
    expect(within(region).getByText('USD')).toBeInTheDocument();
    expect(within(region).getByText('2024-06-01')).toBeInTheDocument();
    expect(within(region).getByText('2024-06-01 08:00')).toBeInTheDocument();
  });

  it('renders a placeholder for unavailable values instead of an empty gap', () => {
    render(<ContextBar store="Only Store" />);
    const region = screen.getByRole('region', { name: '数据上下文' });
    // Five of the six facets are unset → five placeholders.
    expect(within(region).getAllByText('—')).toHaveLength(5);
  });
});
