// Render tests for <OperationActions>.
//
// Confirms the component renders exactly the controls the Requirement 56.1
// matrix allows, hides illegal actions, gates Undo per Requirement 8.4, and
// wires each rendered control to its handler.
//
// Validates: Requirements 56.1, 56.2, 8.3, 21.4, 48.7

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { OperationActions } from './OperationActions';
import {
  CANCEL_PLATFORM_OPERATION_LABEL,
  CLOSE_AI_HOSTING_LABEL,
  OPERATION_ACTION_LABELS,
} from './operationActionMatrix';

function renderedActions(): string[] {
  return Array.from(document.querySelectorAll('[data-action]')).map(
    (el) => el.getAttribute('data-action') as string,
  );
}

describe('<OperationActions>', () => {
  it('renders Approve/Reject/Cancel for awaiting_approval', () => {
    render(<OperationActions syncState="awaiting_approval" />);
    expect(renderedActions()).toEqual(['approve', 'reject', 'cancel']);
  });

  it('renders nothing for a state with no available action (local-only)', () => {
    const { container } = render(<OperationActions syncState="local-only" />);
    expect(container).toBeEmptyDOMElement();
    expect(renderedActions()).toEqual([]);
  });

  it('renders nothing for cancel_requested, cancelled, and superseded', () => {
    for (const state of ['cancel_requested', 'cancelled', 'superseded'] as const) {
      const { container, unmount } = render(<OperationActions syncState={state} />);
      expect(container).toBeEmptyDOMElement();
      unmount();
    }
  });

  it('shows Undo on effective only when reversible and before value still valid', () => {
    const { rerender } = render(
      <OperationActions syncState="effective" reversible beforeValueStillValid />,
    );
    expect(renderedActions()).toEqual(['undo']);

    rerender(<OperationActions syncState="effective" reversible={false} beforeValueStillValid />);
    expect(renderedActions()).toEqual([]);

    rerender(<OperationActions syncState="effective" reversible beforeValueStillValid={false} />);
    expect(renderedActions()).toEqual([]);
  });

  it('labels the Cancel control with platform-operation copy, not the hosting copy', () => {
    render(<OperationActions syncState="submitted" />);
    expect(screen.getByText(CANCEL_PLATFORM_OPERATION_LABEL)).toBeInTheDocument();
    expect(screen.queryByText(CLOSE_AI_HOSTING_LABEL)).not.toBeInTheDocument();
  });

  it('invokes the handler for a rendered action', async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    render(<OperationActions syncState="failed" onRetry={onRetry} />);
    await user.click(screen.getByText(OPERATION_ACTION_LABELS.retry));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('disables all controls when disabled is set', () => {
    render(<OperationActions syncState="awaiting_approval" disabled />);
    for (const el of document.querySelectorAll('[data-action]')) {
      expect(el).toBeDisabled();
    }
  });
});
