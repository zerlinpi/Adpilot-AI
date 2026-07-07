import { AlertTriangle, RefreshCw } from 'lucide-react';

interface ErpErrorStateProps {
  message?: string;
  onRetry?: () => void;
}

export function ErpErrorState({ message = '加载失败，请稍后重试', onRetry }: ErpErrorStateProps) {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <div className="w-12 h-12 rounded-xl bg-destructive/10 flex items-center justify-center mb-4">
        <AlertTriangle size={24} className="text-destructive" />
      </div>
      <h3 className="text-base font-medium text-foreground mb-1">出错了</h3>
      <p className="text-sm text-muted-foreground mb-4">{message}</p>
      {onRetry && (
        <button
          onClick={onRetry}
          className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-foreground bg-card border border-border rounded-lg hover:bg-accent transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <RefreshCw size={14} />
          重试
        </button>
      )}
    </div>
  );
}
