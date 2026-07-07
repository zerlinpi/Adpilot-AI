export function ErpLoadingSkeleton({ rows = 5 }: { rows?: number }) {
  return (
    <div className="space-y-3">
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className="flex items-center gap-4 p-4 bg-card rounded-lg border border-border">
          <div className="h-4 bg-muted rounded w-1/4 animate-pulse" />
          <div className="h-4 bg-muted rounded w-1/6 animate-pulse" />
          <div className="h-4 bg-muted rounded w-1/5 animate-pulse" />
          <div className="h-4 bg-muted rounded w-1/6 animate-pulse" />
          <div className="flex-1" />
          <div className="h-4 bg-muted rounded w-16 animate-pulse" />
        </div>
      ))}
    </div>
  );
}
