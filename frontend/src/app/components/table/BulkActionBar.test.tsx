// Unit and property tests for the standalone BulkActionBar building block.
//
// Validates: Requirements 1.4, 1.9, 2.5, 2.6

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import fc from 'fast-check';

import {
  BulkActionBar,
  activateBulkOperation,
  applicableOperations,
  shouldShowBar,
  type BulkOperation,
} from './BulkActionBar';

function makeOp(overrides: Partial<BulkOperation> = {}): BulkOperation {
  return {
    id: overrides.id ?? 'pause',
    label: overrides.label ?? '批量暂停',
    handler: overrides.handler ?? vi.fn(),
    ...overrides,
  };
}

describe('BulkActionBar visibility (Req 1.4)', () => {
  it('renders nothing when no rows are selected', () => {
    const { container } = render(
      <BulkActionBar selectedIds={[]} operations={[makeOp()]} />,
    );
    expect(container.firstChild).toBeNull();
  });

  it('displays the bar with the applicable operations when rows are selected', () => {
    render(
      <BulkActionBar
        selectedIds={['r1', 'r2']}
        operations={[makeOp({ id: 'pause', label: '批量暂停' })]}
      />,
    );
    expect(screen.getByRole('toolbar', { name: '批量操作' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '批量暂停' })).toBeInTheDocument();
    expect(screen.getByText('已选择 2 项')).toBeInTheDocument();
  });

  it('shows only operations applicable to the current selection (Req 1.4)', () => {
    render(
      <BulkActionBar
        selectedIds={['r1']}
        operations={[
          makeOp({ id: 'pause', label: '批量暂停' }),
          makeOp({
            id: 'merge',
            label: '合并',
            // merge only applies to 2+ rows
            isApplicable: (ids) => ids.length >= 2,
          }),
        ]}
      />,
    );
    expect(screen.getByRole('button', { name: '批量暂停' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '合并' })).not.toBeInTheDocument();
  });
});

describe('BulkActionBar activation (Req 2.5)', () => {
  it('passes the selected row ids to the operation handler', async () => {
    const handler = vi.fn();
    const user = userEvent.setup();
    render(
      <BulkActionBar
        selectedIds={['r1', 'r2', 'r3']}
        operations={[makeOp({ id: 'pause', label: '批量暂停', handler })]}
      />,
    );
    await user.click(screen.getByRole('button', { name: '批量暂停' }));
    expect(handler).toHaveBeenCalledTimes(1);
    expect(handler).toHaveBeenCalledWith(['r1', 'r2', 'r3']);
  });
});

describe('BulkActionBar rejects activation with no selection (Req 2.6)', () => {
  it('does not invoke the handler and returns an indication when empty', () => {
    const handler = vi.fn();
    const result = activateBulkOperation(
      makeOp({ handler }),
      [],
    );
    expect(handler).not.toHaveBeenCalled();
    expect(result.invoked).toBe(false);
    expect(result.message).toBe('请至少选择一行');
  });

  it('invokes the handler with the selected ids when non-empty (Req 2.5)', () => {
    const handler = vi.fn();
    const result = activateBulkOperation(makeOp({ handler }), ['a', 'b']);
    expect(result.invoked).toBe(true);
    expect(handler).toHaveBeenCalledWith(['a', 'b']);
  });
});

describe('shouldShowBar helper (Req 1.4)', () => {
  it('is false for an empty selection and true otherwise', () => {
    expect(shouldShowBar([])).toBe(false);
    expect(shouldShowBar(['x'])).toBe(true);
  });
});

describe('applicableOperations helper (Req 1.4)', () => {
  it('keeps operations without a predicate and filters by predicate', () => {
    const always = makeOp({ id: 'a' });
    const conditional = makeOp({ id: 'b', isApplicable: (ids) => ids.length > 1 });
    expect(applicableOperations([always, conditional], ['r1']).map((o) => o.id)).toEqual([
      'a',
    ]);
    expect(
      applicableOperations([always, conditional], ['r1', 'r2']).map((o) => o.id),
    ).toEqual(['a', 'b']);
  });
});

describe('BulkActionBar properties', () => {
  it('Req 1.4: the bar is visible iff at least one row is selected', () => {
    fc.assert(
      fc.property(fc.array(fc.string()), (ids) => {
        const { container, unmount } = render(
          <BulkActionBar selectedIds={ids} operations={[makeOp()]} />,
        );
        const visible = container.firstChild !== null;
        unmount();
        expect(visible).toBe(ids.length > 0);
      }),
    );
  });

  it('Req 2.5: activating an operation passes exactly the selected ids', async () => {
    await fc.assert(
      fc.asyncProperty(
        fc.array(fc.string({ minLength: 1 }), { minLength: 1, maxLength: 12 }),
        async (ids) => {
          const handler = vi.fn();
          const user = userEvent.setup({ delay: null });
          const { unmount } = render(
            <BulkActionBar
              selectedIds={ids}
              operations={[makeOp({ id: 'op', label: 'op', handler })]}
            />,
          );
          await user.click(screen.getByRole('button', { name: 'op' }));
          unmount();
          expect(handler).toHaveBeenCalledTimes(1);
          expect(handler).toHaveBeenCalledWith(ids);
        },
      ),
      { numRuns: 25 },
    );
  }, 30000);

  it('Req 2.6: activation is rejected exactly when the selection is empty', () => {
    fc.assert(
      fc.property(fc.array(fc.string()), (ids) => {
        const handler = vi.fn();
        const result = activateBulkOperation(makeOp({ handler }), ids);
        if (ids.length === 0) {
          expect(result.invoked).toBe(false);
          expect(result.message).toBeTruthy();
          expect(handler).not.toHaveBeenCalled();
        } else {
          expect(result.invoked).toBe(true);
          expect(handler).toHaveBeenCalledWith(ids);
        }
      }),
    );
  });

  it('Req 1.4 + 1.4: every rendered operation button is applicable to the selection', () => {
    fc.assert(
      fc.property(
        fc.array(fc.string({ minLength: 1 }), { minLength: 1, maxLength: 8 }),
        fc.integer({ min: 1, max: 6 }),
        (ids, threshold) => {
          const ops: BulkOperation[] = [
            makeOp({ id: 'always', label: 'always' }),
            makeOp({
              id: 'conditional',
              label: 'conditional',
              isApplicable: (s) => s.length >= threshold,
            }),
          ];
          const { unmount } = render(
            <BulkActionBar selectedIds={ids} operations={ops} />,
          );
          const conditionalShown =
            screen.queryByRole('button', { name: 'conditional' }) !== null;
          unmount();
          expect(conditionalShown).toBe(ids.length >= threshold);
        },
      ),
    );
  });
});
