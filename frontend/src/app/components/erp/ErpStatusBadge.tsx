import { cn } from '../../lib/utils';

const statusMap: Record<string, { label: string; bg: string; text: string }> = {
  active: { label: '运行中', bg: 'bg-emerald-50', text: 'text-emerald-700' },
  paused: { label: '已暂停', bg: 'bg-muted', text: 'text-muted-foreground' },
  completed: { label: '已完成', bg: 'bg-blue-50', text: 'text-blue-700' },
  draft: { label: '草稿', bg: 'bg-muted', text: 'text-muted-foreground' },
  pending: { label: '待处理', bg: 'bg-amber-50', text: 'text-amber-700' },
  approved: { label: '已审批', bg: 'bg-emerald-50', text: 'text-emerald-700' },
  rejected: { label: '已拒绝', bg: 'bg-red-50', text: 'text-red-700' },
  open: { label: '待处理', bg: 'bg-amber-50', text: 'text-amber-700' },
  resolved: { label: '已解决', bg: 'bg-emerald-50', text: 'text-emerald-700' },
  ignored: { label: '已忽略', bg: 'bg-muted', text: 'text-muted-foreground' },
  connected: { label: '已连接', bg: 'bg-emerald-50', text: 'text-emerald-700' },
  disconnected: { label: '未连接', bg: 'bg-muted', text: 'text-muted-foreground' },
  learning: { label: '学习期', bg: 'bg-blue-50', text: 'text-blue-700' },
  limited_budget: { label: '预算受限', bg: 'bg-amber-50', text: 'text-amber-700' },
  needs_review: { label: '待检查', bg: 'bg-orange-50', text: 'text-orange-700' },
  in_progress: { label: '处理中', bg: 'bg-blue-50', text: 'text-blue-700' },
  waiting_approval: { label: '待审批', bg: 'bg-amber-50', text: 'text-amber-700' },
  dismissed: { label: '已忽略', bg: 'bg-muted', text: 'text-muted-foreground' },
  failed: { label: '失败', bg: 'bg-red-50', text: 'text-red-700' },
  success: { label: '成功', bg: 'bg-emerald-50', text: 'text-emerald-700' },
  cancelled: { label: '已取消', bg: 'bg-muted', text: 'text-muted-foreground' },
  closed: { label: '已关闭', bg: 'bg-muted', text: 'text-muted-foreground' },
  config_error: { label: '配置错误', bg: 'bg-red-50', text: 'text-red-700' },
  token_expired: { label: '令牌过期', bg: 'bg-amber-50', text: 'text-amber-700' },
  waiting: { label: '等待中', bg: 'bg-muted', text: 'text-muted-foreground' },
  running: { label: '运行中', bg: 'bg-blue-50', text: 'text-blue-700' },
  validating: { label: '校验中', bg: 'bg-blue-50', text: 'text-blue-700' },
  ready: { label: '待审批', bg: 'bg-amber-50', text: 'text-amber-700' },
  exported: { label: '已导出', bg: 'bg-blue-50', text: 'text-blue-700' },
  submitted: { label: '已提交', bg: 'bg-blue-50', text: 'text-blue-700' },
  uploaded: { label: '已上传', bg: 'bg-blue-50', text: 'text-blue-700' },
  previewed: { label: '已预览', bg: 'bg-blue-50', text: 'text-blue-700' },
  mapping: { label: '映射中', bg: 'bg-blue-50', text: 'text-blue-700' },
  importing: { label: '导入中', bg: 'bg-blue-50', text: 'text-blue-700' },
  inactive: { label: '停用', bg: 'bg-muted', text: 'text-muted-foreground' },
  pending_arrival: { label: '待到货', bg: 'bg-amber-50', text: 'text-amber-700' },
  in_transit: { label: '运输中', bg: 'bg-blue-50', text: 'text-blue-700' },
  delivered: { label: '已送达', bg: 'bg-emerald-50', text: 'text-emerald-700' },
  receiving: { label: '收货中', bg: 'bg-orange-50', text: 'text-orange-700' },
  shipped: { label: '已发货', bg: 'bg-blue-50', text: 'text-blue-700' },
  working: { label: '处理中', bg: 'bg-blue-50', text: 'text-blue-700' },
  error: { label: '异常', bg: 'bg-red-50', text: 'text-red-700' },
  checked_in: { label: '已签到', bg: 'bg-blue-50', text: 'text-blue-700' },
};

export function ErpStatusBadge({ status }: { status: string }) {
  const config = statusMap[status] || { label: status, bg: 'bg-muted', text: 'text-muted-foreground' };
  return (
    <span className={cn('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium', config.bg, config.text)}>
      {config.label}
    </span>
  );
}
