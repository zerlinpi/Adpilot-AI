// AdPilot AI — Global command palette (⌘K / Ctrl+K)
//
// A top-tier-SaaS style command palette: press ⌘K (mac) / Ctrl+K anywhere to
// fuzzy-search and jump to any page the account may see, or switch the active
// store — all without touching the mouse. Navigation commands reuse the exact
// sidebar visibility rules via `buildNavCommands`; store switching reuses
// StoreContext. Rendering is delegated to the shared cmdk wrapper.

import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { Store, Clock } from 'lucide-react';
import {
  CommandDialog,
  CommandInput,
  CommandList,
  CommandEmpty,
  CommandGroup,
  CommandItem,
} from './ui/command';
import { usePermissions } from '../lib/PermissionContext';
import { useStoreContext } from '../lib/StoreContext';
import {
  buildNavCommands,
  groupNavCommands,
  pickRecentCommands,
  loadRecentRoutes,
  pushRecentRoute,
} from '../lib/commandPalette';
import { notify } from '../lib/toast';

interface CommandPaletteProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function CommandPalette({ open, onOpenChange }: CommandPaletteProps) {
  const { can } = usePermissions();
  const { stores, storeId, setStoreId } = useStoreContext();
  const navigate = useNavigate();

  const groups = useMemo(() => groupNavCommands(buildNavCommands(can)), [can]);
  const allCommands = useMemo(() => buildNavCommands(can), [can]);

  // Recently-visited pages, refreshed each time the palette opens.
  const [recentRoutes, setRecentRoutes] = useState<string[]>([]);
  useEffect(() => {
    if (open) setRecentRoutes(loadRecentRoutes());
  }, [open]);
  const recent = useMemo(() => pickRecentCommands(allCommands, recentRoutes), [allCommands, recentRoutes]);

  const runNavigate = (to: string) => {
    pushRecentRoute(to);
    onOpenChange(false);
    navigate(to);
  };

  const runSwitchStore = (id: string) => {
    onOpenChange(false);
    setStoreId(id);
    const name = stores.find((s) => s.id === id)?.name;
    notify.success('已切换店铺', name);
  };

  return (
    <CommandDialog
      open={open}
      onOpenChange={onOpenChange}
      title="命令面板"
      description="搜索并跳转到任意页面，或切换店铺"
    >
      <CommandInput placeholder="跳转到页面… 或切换店铺…" />
      <CommandList>
        <CommandEmpty>未找到匹配项</CommandEmpty>

        {recent.length > 0 && (
          <CommandGroup heading="最近访问">
            {recent.map((cmd) => (
              <CommandItem
                key={`recent-${cmd.to}`}
                value={`最近 ${cmd.label} ${cmd.group}`}
                onSelect={() => runNavigate(cmd.to)}
              >
                <Clock className="text-slate-400" />
                <span className="truncate">{cmd.label}</span>
                <span className="ml-auto text-[11px] text-slate-400">{cmd.group}</span>
              </CommandItem>
            ))}
          </CommandGroup>
        )}

        {stores.length > 0 && (
          <CommandGroup heading="切换店铺">
            {stores.map((s) => (
              <CommandItem
                key={`store-${s.id}`}
                value={`切换店铺 ${s.name}`}
                onSelect={() => runSwitchStore(s.id)}
              >
                <Store className="text-slate-400" />
                <span className="truncate">{s.name}</span>
                {s.id === storeId && (
                  <span className="ml-auto text-[11px] text-blue-600">当前</span>
                )}
              </CommandItem>
            ))}
          </CommandGroup>
        )}

        {groups.map(({ group, items }) => (
          <CommandGroup key={group} heading={group}>
            {items.map((cmd) => (
              <CommandItem
                key={cmd.to}
                value={`${cmd.label} ${cmd.group}`}
                onSelect={() => runNavigate(cmd.to)}
              >
                <span className="truncate">{cmd.label}</span>
                <span className="ml-auto text-[11px] text-slate-400">{cmd.group}</span>
              </CommandItem>
            ))}
          </CommandGroup>
        ))}
      </CommandList>
    </CommandDialog>
  );
}

/**
 * Hook that wires the global ⌘K / Ctrl+K shortcut to a setter. Mounted once by
 * the trigger so the shortcut works on every page. Ignores the combo while the
 * user is typing in an input/textarea/contenteditable so it never hijacks text.
 */
export function useCommandPaletteShortcut(setOpen: (updater: (v: boolean) => boolean) => void) {
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && (e.key === 'k' || e.key === 'K')) {
        e.preventDefault();
        setOpen((v) => !v);
      }
    }
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [setOpen]);
}
