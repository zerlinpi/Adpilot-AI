// BulkActionBar — a reusable, standalone table building block.
//
// Displays a bar of bulk operations that act on the currently selected table
// rows. The bar is shown ONLY when one or more rows are selected and hidden
// when the selection is empty. Activating an operation passes the set of
// selected row identifiers to that operation's handler. Activating an
// operation with no rows selected is rejected with an indication that at least
// one row must be selected.
//
// Validates: Requirements 1.4, 1.9, 2.5, 2.6

import * as React from 'react';
import { X } from 'lucide-react';

import { Button, buttonVariants } from '../ui/button';
import { Badge } from '../ui/badge';
import { cn } from '../../lib/utils';

import type { BulkSelectionScope } from './tableSelection';

import type { VariantProps } from 'class-variance-authority';

type ButtonVariant = VariantProps<typeof buttonVariants>['variant'];

/**
 * A single bulk operation that can be applied to a set of selected rows.
 *
 * The generic `Row` parameter lets host pages document the row shape the
 * operation conceptually targets; the handler itself receives the selected row
 * identifiers (as resolved by the host table's `rowId`).
 */
export interface BulkOperation<Row = unknown> {
  /** Stable identifier for the operation, used as a React key. */
  id: string;
  /** Human-readable label rendered on the operation's button. */
  label: string;
  /**
   * Invoked with the set of selected row identifiers when the operation is
   * activated with a non-empty selection (Req 2.5). May be async.
   */
  handler: (selectedIds: string[]) => void | Promise<void>;
  /**
   * Optional cross-page-aware handler (Req 44.3). When the activation carries a
   * {@link BulkSelectionScope} (e.g. "all filtered results"), this is invoked
   * with the scope so the host can apply the Bulk_Operation to EVERY matching
   * record across all pages rather than just the rendered ids. When omitted,
   * the operation falls back to {@link handler} with the explicit ids.
   */
  scopedHandler?: (scope: BulkSelectionScope) => void | Promise<void>;
  /** Optional button variant; defaults to the host button's default style. */
  variant?: ButtonVariant;
  /** Optional leading icon node. */
  icon?: React.ReactNode;
  /** When true, the operation's button is rendered disabled. */
  disabled?: boolean;
  /**
   * Optional predicate deciding whether this operation applies to the current
   * selection. When omitted, the operation is always applicable. Only
   * applicable operations are displayed (Req 1.4).
   */
  isApplicable?: (selectedIds: string[]) => boolean;
  // The `Row` type parameter is intentionally only used for host-side typing.
  __row?: Row;
}

export interface BulkActionBarProps<Row = unknown> {
  /** The identifiers of the currently selected rows. */
  selectedIds: string[];
  /** The bulk operations exposed by the host page. */
  operations: BulkOperation<Row>[];
  /** Optional callback to clear the current selection. */
  onClearSelection?: () => void;
  /**
   * The resolved cross-page selection scope (Req 44.2–44.4). When present and
   * its kind is `all_filtered`, the selection covers every record matching the
   * active filters across all pages, the count badge reflects the covered
   * count, and an operation's {@link BulkOperation.scopedHandler} (when
   * present) is invoked with this scope instead of the explicit page ids.
   */
  selectionScope?: BulkSelectionScope;
  /** Message shown when an operation is activated with no selection (Req 2.6). */
  noSelectionMessage?: string;
  /** Label template for the selection count badge. Receives the count. */
  selectionLabel?: (count: number) => string;
  className?: string;
}

const DEFAULT_NO_SELECTION_MESSAGE = '请至少选择一行';

const defaultSelectionLabel = (count: number) => `已选择 ${count} 项`;

/**
 * Returns true when the bar should be visible — i.e. when at least one row is
 * selected (Req 1.4), or an "all filtered results" selection is active (Req
 * 44.3) even though the current page's explicit id set may be empty.
 */
export function shouldShowBar(
  selectedIds: readonly string[],
  selectionScope?: BulkSelectionScope,
): boolean {
  if (selectionScope?.kind === 'all_filtered') return true;
  return selectedIds.length > 0;
}

/**
 * Filters the supplied operations down to those applicable to the current
 * selection. Operations without an `isApplicable` predicate are always
 * applicable (Req 1.4).
 */
export function applicableOperations<Row>(
  operations: BulkOperation<Row>[],
  selectedIds: string[],
): BulkOperation<Row>[] {
  return operations.filter(
    (op) => op.isApplicable === undefined || op.isApplicable(selectedIds),
  );
}

/** Result of attempting to activate a bulk operation. */
export interface ActivationResult {
  /** True when the operation handler was invoked. */
  invoked: boolean;
  /** Indication message when the activation was rejected. */
  message?: string;
}

/**
 * Pure activation contract reusable by any host (e.g. SharedDataTable).
 *
 * Invokes the operation with a copy of the selected row identifiers when at
 * least one row is selected (Req 2.5). Rejects the activation without invoking
 * the operation when the selection is empty, returning an indication that at
 * least one row must be selected (Req 2.6).
 *
 * When a cross-page {@link BulkSelectionScope} is supplied:
 *   - an `all_filtered` scope is a NON-empty selection even if no explicit ids
 *     are present (it covers every matching record, Req 44.3); and
 *   - if the operation exposes a {@link BulkOperation.scopedHandler}, it is
 *     invoked with the scope so the host applies the operation to every
 *     matching record rather than just the rendered ids.
 */
export function activateBulkOperation<Row>(
  operation: BulkOperation<Row>,
  selectedIds: string[],
  noSelectionMessage: string = DEFAULT_NO_SELECTION_MESSAGE,
  selectionScope?: BulkSelectionScope,
): ActivationResult {
  const isAllFiltered = selectionScope?.kind === 'all_filtered';
  if (selectedIds.length === 0 && !isAllFiltered) {
    return { invoked: false, message: noSelectionMessage };
  }
  if (selectionScope && operation.scopedHandler) {
    void operation.scopedHandler(selectionScope);
  } else {
    void operation.handler([...selectedIds]);
  }
  return { invoked: true };
}

export function BulkActionBar<Row = unknown>({
  selectedIds,
  operations,
  onClearSelection,
  selectionScope,
  noSelectionMessage = DEFAULT_NO_SELECTION_MESSAGE,
  selectionLabel = defaultSelectionLabel,
  className,
}: BulkActionBarProps<Row>) {
  const [rejectionMessage, setRejectionMessage] = React.useState<string | null>(
    null,
  );

  const hasSelection = shouldShowBar(selectedIds, selectionScope);

  // Clear any stale rejection indication once a selection exists again.
  React.useEffect(() => {
    if (hasSelection) {
      setRejectionMessage(null);
    }
  }, [hasSelection]);

  const activate = React.useCallback(
    (operation: BulkOperation<Row>) => {
      const result = activateBulkOperation(
        operation,
        selectedIds,
        noSelectionMessage,
        selectionScope,
      );
      // Reject activation when no rows are selected; indicate that at least
      // one row must be selected (Req 2.6). Otherwise the handler received the
      // selected ids (Req 2.5) or the cross-page scope (Req 44.3).
      setRejectionMessage(result.invoked ? null : result.message ?? null);
    },
    [selectedIds, noSelectionMessage, selectionScope],
  );

  // Hide the bar entirely when there is no selection at all (Req 1.4).
  if (!hasSelection) {
    return null;
  }

  const ops = applicableOperations(operations, selectedIds);

  // The count badge reflects the COVERED count: for an "all filtered results"
  // selection that is the total matching records across all pages (Req 44.4),
  // otherwise the explicit page selection count.
  const isAllFiltered = selectionScope?.kind === 'all_filtered';
  const countBadge = isAllFiltered
    ? `已选择全部筛选结果（共 ${selectionScope.coveredCount} 项）`
    : selectionLabel(selectedIds.length);

  return (
    <div
      role="toolbar"
      aria-label="批量操作"
      data-slot="bulk-action-bar"
      className={cn(
        'flex flex-wrap items-center gap-3 rounded-md border bg-card px-4 py-2 shadow-sm',
        className,
      )}
    >
      <Badge variant="secondary" data-slot="bulk-selection-count">
        {countBadge}
      </Badge>

      <div className="flex flex-wrap items-center gap-2">
        {ops.map((op) => (
          <Button
            key={op.id}
            type="button"
            size="sm"
            variant={op.variant ?? 'outline'}
            disabled={op.disabled}
            onClick={() => activate(op)}
          >
            {op.icon}
            {op.label}
          </Button>
        ))}
      </div>

      {rejectionMessage && (
        <span role="alert" className="text-sm text-destructive">
          {rejectionMessage}
        </span>
      )}

      {onClearSelection && (
        <Button
          type="button"
          size="sm"
          variant="ghost"
          className="ml-auto"
          onClick={() => {
            setRejectionMessage(null);
            onClearSelection();
          }}
        >
          <X />
          取消选择
        </Button>
      )}
    </div>
  );
}

export default BulkActionBar;
