// Export entry + scope disclosure for the Shared_Data_Table (Req 31.5, 32.1, 37.1).
//
// Renders a toolbar control that, before producing any file, discloses the two
// export scopes — "current page only" vs "all rows matching the active filters"
// — and asks the operator to choose (Req 37.1). The actual dataset (which rows
// and which visible columns) is resolved by the pure `resolveExportDataset`
// helper in the host (`SharedDataTable`), so this component only surfaces the
// choice.

import { Download } from 'lucide-react';

import { Button } from '../ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../ui/dropdown-menu';
import { EXPORT_SCOPE_LABELS, type ExportScope } from './tableExport';

export interface ExportMenuProps {
  /** Invoked with the chosen scope once the operator picks one. */
  onExport: (scope: ExportScope) => void;
  /** Disable the control (e.g. while a request is in progress). */
  disabled?: boolean;
}

/** Export dropdown that discloses scope before producing the file (Req 37.1). */
export function ExportMenu({ onExport, disabled }: ExportMenuProps) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          type="button"
          variant="outline"
          size="sm"
          aria-label="导出"
          disabled={disabled}
          data-slot="table-export"
        >
          <Download className="size-4" />
          导出
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-56">
        <DropdownMenuLabel>选择导出范围</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem
          data-slot="table-export-current-page"
          onSelect={() => onExport('current_page')}
        >
          {EXPORT_SCOPE_LABELS.current_page}
        </DropdownMenuItem>
        <DropdownMenuItem
          data-slot="table-export-all-filtered"
          onSelect={() => onExport('all_filtered')}
        >
          {EXPORT_SCOPE_LABELS.all_filtered}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

export default ExportMenu;
