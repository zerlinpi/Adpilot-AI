// Saved-view entry for the Shared_Data_Table (Req 31.6, 34.3).
//
// Renders a toolbar control that lists the current user's saved views for this
// table and lets them save the current columns/filters/sort as a new named
// view. Saved views are scoped to `(user_id, table_key)` by the backend
// (TableViewController); this component is mounted ONLY when saved views are
// enabled so that the `useSavedViews` react-query hook (and its QueryClient
// dependency) is never required by tables that don't opt in.

import * as React from 'react';
import { Bookmark, Trash2 } from 'lucide-react';

import { Button } from '../ui/button';
import { Input } from '../ui/input';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../ui/dropdown-menu';
import { useSavedViews, type SavedViewConfig } from './useSavedViews';

export interface SavedViewControlProps {
  /** Stable table identity; saved views resolve by `(user_id, this key)`. */
  tableKey: string;
  /** Builds the {@link SavedViewConfig} to persist from the current table state. */
  buildCurrentConfig: () => SavedViewConfig;
  /** Applies a decoded saved view's columns/filters/sort to the host table. */
  onApply: (config: SavedViewConfig) => void;
}

/** Saved-view dropdown wired to the per-user `(user_id, table_key)` endpoints. */
export function SavedViewControl({
  tableKey,
  buildCurrentConfig,
  onApply,
}: SavedViewControlProps) {
  const { views, saveView, isSaving, saveError, applyView, deleteView } =
    useSavedViews(tableKey);

  const [name, setName] = React.useState('');

  const handleSave = React.useCallback(async () => {
    const trimmed = name.trim();
    if (!trimmed) return;
    try {
      await saveView(trimmed, buildCurrentConfig());
      setName('');
    } catch {
      // The rejection message is surfaced via `saveError` below.
    }
  }, [name, saveView, buildCurrentConfig]);

  const handleApply = React.useCallback(
    (id: string) => {
      const config = applyView(id);
      if (config) onApply(config);
    },
    [applyView, onApply],
  );

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          type="button"
          variant="outline"
          size="sm"
          aria-label="视图"
          data-slot="table-saved-views"
        >
          <Bookmark className="size-4" />
          视图
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-64">
        <DropdownMenuLabel>已保存视图</DropdownMenuLabel>
        <DropdownMenuSeparator />
        {views.length === 0 ? (
          <p className="px-2 py-1.5 text-xs text-slate-400">暂无已保存视图</p>
        ) : (
          views.map((view) => (
            <DropdownMenuItem
              key={view.id}
              data-slot="table-saved-view-item"
              onSelect={() => handleApply(view.id)}
              className="flex items-center justify-between gap-2"
            >
              <span className="truncate">{view.name}</span>
              <button
                type="button"
                aria-label={`删除视图 ${view.name}`}
                className="text-slate-400 hover:text-red-500"
                onClick={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  void deleteView(view.id);
                }}
              >
                <Trash2 className="size-3.5" />
              </button>
            </DropdownMenuItem>
          ))
        )}
        <DropdownMenuSeparator />
        <DropdownMenuLabel>保存当前视图</DropdownMenuLabel>
        <div
          className="flex flex-col gap-2 px-2 py-1.5"
          // Keep the menu open while typing / saving.
          onKeyDown={(e) => e.stopPropagation()}
        >
          <Input
            value={name}
            placeholder="视图名称"
            aria-label="视图名称"
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                void handleSave();
              }
            }}
          />
          <Button
            type="button"
            size="sm"
            disabled={isSaving || name.trim().length === 0}
            onClick={() => void handleSave()}
          >
            保存
          </Button>
          {saveError && (
            <p role="alert" className="text-xs text-red-500">
              {saveError}
            </p>
          )}
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

export default SavedViewControl;
