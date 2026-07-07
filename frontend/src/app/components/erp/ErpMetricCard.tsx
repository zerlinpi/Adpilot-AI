import { ReactNode } from 'react';
import { cn } from '../../lib/utils';

interface ErpMetricCardProps {
  label: string;
  value: string | number;
  change?: number;
  icon?: ReactNode;
  className?: string;
}

export function ErpMetricCard({ label, value, change, icon, className }: ErpMetricCardProps) {
  return (
    <div className={cn('bg-card rounded-lg border border-border p-4', className)}>
      <div className="flex items-center justify-between mb-2">
        <span className="text-xs text-muted-foreground">{label}</span>
        {icon}
      </div>
      <p className="text-xl font-semibold text-foreground">{value}</p>
      {change !== undefined && (
        <p className={cn('text-xs mt-1', change >= 0 ? 'text-emerald-600' : 'text-red-600')}>
          {change >= 0 ? '+' : ''}{change}%
        </p>
      )}
    </div>
  );
}
