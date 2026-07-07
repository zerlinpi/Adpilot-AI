// Interaction tests for <ApprovalActions> (Req 24.1–24.3, 10.5, 10.6).
//
// Confirms the component:
//  - shows Approve/Reject only for awaiting_approval and only with
//    advertising:approve (Req 24.1), and Reject collects a reason (Req 24.3);
//  - shows Rollback only for effective + reversible operations with
//    advertising:execute, and not for non-reversible decision types (Req 10.6);
//  - surfaces the conflicting operation ids + warning on a confirmation_required
//    rollback response and re-issues with confirm=true on "仍要回滚" (Req 10.5);
//  - renders nothing when no action applies or no permission is held.

import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { ApprovalActions } from './ApprovalActions';
import type { HostingDecision, HostingRollbackResult } from '../../lib/api';

function decision(overrides: Partial<HostingDecision> = {}): HostingDecision {
  return {
    id: 'dec-1',
    store_id: 'store-1',
    campaign_id: 'camp-1',
    decision_type: 'bid_adjustment',
    sync_state: 'awaiting_approval',
    promoted_operation_id: 'op-1',
    ...overrides,
  };
}

const noop = {
  onApprove: () => Promise.resolve(),
  onReject: () => Promise.resolve(),
  onRollback: (): Promise<HostingRollbackResult> => Promise.resolve({ confirmation_required: false }),
};

describe('<ApprovalActions>', () => {
  it('shows Approve/Reject for awaiting_approval with advertising:approve (Req 24.1)', () => {
    render(<ApprovalActions decision={decision()} canApprove canRollback={false} {...noop} />);
    expect(screen.getByRole('button', { name: /批准/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /拒绝/ })).toBeInTheDocument();
  });

  it('hides Approve/Reject without advertising:approve (Req 24.1)', () => {
    const { container } = render(
      <ApprovalActions decision={decision()} canApprove={false} canRollback={false} {...noop} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('calls onApprove with the operation id (Req 24.2)', async () => {
    const onApprove = vi.fn().mockResolvedValue(undefined);
    render(<ApprovalActions decision={decision()} canApprove canRollback={false} {...noop} onApprove={onApprove} />);
    await userEvent.click(screen.getByRole('button', { name: /批准/ }));
    expect(onApprove).toHaveBeenCalledWith('op-1');
  });

  it('collects a reason before rejecting (Req 24.3)', async () => {
    const onReject = vi.fn().mockResolvedValue(undefined);
    render(<ApprovalActions decision={decision()} canApprove canRollback={false} {...noop} onReject={onReject} />);

    await userEvent.click(screen.getByRole('button', { name: /拒绝/ }));
    const input = screen.getByLabelText('拒绝原因');
    await userEvent.type(input, '预算过高');
    await userEvent.click(screen.getByRole('button', { name: '确认拒绝' }));

    expect(onReject).toHaveBeenCalledWith('op-1', '预算过高');
  });

  it('shows Rollback for effective reversible operations with advertising:execute (Req 10.6)', () => {
    render(
      <ApprovalActions
        decision={decision({ sync_state: 'effective', decision_type: 'budget_adjustment' })}
        canApprove={false}
        canRollback
        {...noop}
      />,
    );
    expect(screen.getByRole('button', { name: /回滚/ })).toBeInTheDocument();
  });

  it('does not show Rollback for non-reversible decision types (Req 10.3)', () => {
    const { container } = render(
      <ApprovalActions
        decision={decision({ sync_state: 'effective', decision_type: 'keyword_addition' })}
        canApprove={false}
        canRollback
        {...noop}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('surfaces conflict warning then confirms rollback with confirm=true (Req 10.5)', async () => {
    const onRollback = vi
      .fn()
      .mockResolvedValueOnce({
        confirmation_required: true,
        conflicting_operation_ids: ['op-9'],
        warning_message: '存在后续变更',
      } satisfies HostingRollbackResult)
      .mockResolvedValueOnce({ confirmation_required: false } satisfies HostingRollbackResult);

    render(
      <ApprovalActions
        decision={decision({ sync_state: 'effective', decision_type: 'bid_adjustment' })}
        canApprove={false}
        canRollback
        {...noop}
        onRollback={onRollback}
      />,
    );

    await userEvent.click(screen.getByRole('button', { name: /回滚/ }));
    await waitFor(() => expect(screen.getByText('存在后续变更')).toBeInTheDocument());
    expect(screen.getByText(/op-9/)).toBeInTheDocument();
    expect(onRollback).toHaveBeenNthCalledWith(1, 'op-1', false);

    await userEvent.click(screen.getByRole('button', { name: '仍要回滚' }));
    expect(onRollback).toHaveBeenNthCalledWith(2, 'op-1', true);
  });

  it('renders nothing when the decision has no promoted operation', () => {
    const { container } = render(
      <ApprovalActions
        decision={decision({ promoted_operation_id: null })}
        canApprove
        canRollback
        {...noop}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
