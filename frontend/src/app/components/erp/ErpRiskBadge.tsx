import { cn } from '../../lib/utils';

const riskMap: Record<string, { label: string; bg: string; text: string }> = {
  low: { label: '低风险', bg: 'bg-emerald-50', text: 'text-emerald-700' },
  medium: { label: '中风险', bg: 'bg-amber-50', text: 'text-amber-700' },
  high: { label: '高风险', bg: 'bg-red-50', text: 'text-red-700' },
};

export function ErpRiskBadge({ level }: { level: string }) {
  const config = riskMap[level] || { label: level, bg: 'bg-muted', text: 'text-muted-foreground' };
  return (
    <span className={cn('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium', config.bg, config.text)}>
      {config.label}
    </span>
  );
}
