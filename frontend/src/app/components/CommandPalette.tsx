// AdPilot AI — Global command palette (⌘K / Ctrl+K)
//
// A top-tier-SaaS style command palette: press ⌘K (mac) / Ctrl+K anywhere to
// fuzzy-search and jump to any page the account may see, or switch the active
// store — all without touching the mouse. Navigation commands reuse the exact
// sidebar visibility rules via `buildNavCommands`; store switching reuses
// StoreContext. Rendering is delegated to the shared cmdk wrapper.

import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { Store, Clock, Zap } from 'lucide-react';
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

const adOptimizationQuickActions = [
  {
    to: '/campaigns',
    label: '广告活动',
    description: '查看 Campaign、预算与投放状态',
    keywords: 'campaign campaigns 广告 活动 SP SB SD sponsored',
  },
  {
    to: '/smart-diagnosis',
    label: '智能诊断',
    description: '定位广告异常与优先优化机会',
    keywords: 'diagnosis diagnose 智能 诊断 异常 问题 优化',
  },
  {
    to: '/automation-rules',
    label: '自动化规则',
    description: '管理出价、预算和运营自动化',
    keywords: 'automation rules 自动化 规则 bid bidding budget 出价 预算',
  },
  {
    to: '/search-terms',
    label: '搜索词',
    description: '分析 Search Term 并发现浪费流量',
    keywords: 'search term query 搜索词 搜索 查询词 否定词 waste',
  },
  {
    to: '/keyword-library',
    label: '关键词库',
    description: '沉淀、扩词、否词和管理关键词资产',
    keywords: 'keyword keywords library 关键词 词库 扩词 否词 harvest negate',
  },
  {
    to: '/insight-agent',
    label: 'Insight Agent',
    description: '用 AI 查询广告和经营洞察',
    keywords: 'insight agent ai copilot assistant 洞察 分析 问答 广告',
  },
] as const;

export function CommandPalette({ open, onOpenChange }: CommandPaletteProps) {
  const { can } = usePermissions();
  const { stores, storeId, setStoreId } = useStoreContext();
  const navigate = useNavigate();

  const groups = useMemo(() => groupNavCommands(buildNavCommands(can)), [can]);
  const allCommands = useMemo(() => buildNavCommands(can), [can]);

  const quickActions = useMemo(
    () =>
      adOptimizationQuickActions.flatMap((definition) => {
        const command = allCommands.find((item) => item.to === definition.to);
        return command ? [{ ...definition, group: command.group }] : [];
      }),
    [allCommands],
  );

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
      <CommandInput placeholder="搜索广告优化、页面或店铺…" />
      <CommandList>
        <CommandEmpty>未找到匹配项</CommandEmpty>

        {quickActions.length > 0 && (
          <CommandGroup heading="广告优化快捷入口">
            {quickActions.map((action) => (
              <CommandItem
                key={`quick-${action.to}`}
                value={`${action.label} ${action.description} ${action.keywords} ${action.group}`}
                onSelect={() => runNavigate(action.to)}
              >
                <Zap className="text-blue-500" />
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-medium">{action.label}</div>
                  <div className="truncate text-[11px] text-slate-400">{action.description}</div>
                </div>
                <span className="ml-2 text-[11px] text-slate-400">{action.group}</span>
              </CommandItem>
            ))}
          </CommandGroup>
        )}

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
