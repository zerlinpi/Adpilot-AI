// Column-management entry + UI for the Shared_Data_Table (Req 31.4, 32.1).
//
// Renders a toolbar control that opens a checklist of every managed column,
// letting the operator show/hide columns. It is driven entirely by the
// `useColumnConfig` hook state passed down from `SharedDataTable`, so the
// at-least-one-visible invariant (the hook rejects hiding the last visible
// column) is enforced centrally: the checkbox for the sole remaining visible
// column is disabled and the hook's rejection message is surfaced.

import { Columns3 } from 'lucide-react';

import { Button } from '../ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuCheckboxItem,
  DropdownMenuTrigger,
} from '../ui/dropdown-menu';

export interface ColumnManagementMenuProps {
  /** All managed columns, in their configured order. */
  columns: ReadonlyArray<{ key: string; header: string }>;
  /** True when the given column key is currently visible. */
  isVisible: (key: string) => boolean;
  /** True when hiding the given column is currently permitted. */
  canHide: (key: string) => boolean;
  /** Toggle a column's visibility (honoring the at-least-one-visible rule). */
  onToggle: (key: string) => void;
  /** The most recent rejection message from the column-config hook, if any. */
  lastError?: string | null;
}

/** Column-management dropdown. Pure presentation over the column-config hook. */
export function ColumnManagementMenu({
  columns,
  isVisible,
  canHide,
  onToggle,
  lastError,
}: ColumnManagementMenuProps) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          type="button"
          variant="outline"
          size="sm"
          aria-label="列管理"
          data-slot="table-column-management"
        >
          <Columns3 className="size-4" />
          列管理
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-52">
        <DropdownMenuLabel>显示列</DropdownMenuLabel>
        <DropdownMenuSeparator />
        {columns.map((col) => {
          const visible = isVisible(col.key);
          // Disable un-checking the sole remaining visible column.
          const disabled = visible && !canHide(col.key);
          return (
            <DropdownMenuCheckboxItem
              key={col.key}
              checked={visible}
              disabled={disabled}
              // Keep the menu open so several columns can be toggled at once.
              onSelect={(e) => e.preventDefault()}
              onCheckedChange={() => onToggle(col.key)}
            >
              {col.header || col.key}
            </DropdownMenuCheckboxItem>
          );
        })}
        {lastError && (
          <>
            <DropdownMenuSeparator />
            <p
              role="alert"
              className="px-2 py-1.5 text-xs text-amber-600"
              data-slot="table-column-management-error"
            >
              {lastError}
            </p>
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

export default ColumnManagementMenu;
