// Recharts trend chart extracted from CampaignsTab's TrendPanel so it can be
// lazy-loaded (React.lazy) on demand. Keeping the recharts (+ d3) dependency
// behind a dynamic import keeps the "charts" bundle out of the campaigns
// workspace page chunk until a trend chart is actually rendered.

import {
  Area, CartesianGrid, ComposedChart, Legend, Line, ResponsiveContainer,
  Tooltip, XAxis, YAxis,
} from 'recharts';

import { formatCurrency } from '../../../lib/utils';
import type { CampaignTrendPoint } from '../../../lib/api';

export function CampaignTrendChart({ trend }: { trend: CampaignTrendPoint[] }) {
  return (
    <ResponsiveContainer width="100%" height="100%">
      <ComposedChart data={trend} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
        <defs>
          <linearGradient id="campaignSpendGradient" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#3B82F6" stopOpacity={0.3} />
            <stop offset="95%" stopColor="#3B82F6" stopOpacity={0} />
          </linearGradient>
        </defs>
        <CartesianGrid strokeDasharray="3 3" stroke="#E2E8F0" vertical={false} />
        <XAxis dataKey="period" tick={{ fontSize: 12, fill: '#94A3B8' }} axisLine={false} tickLine={false} />
        <YAxis
          yAxisId="spend"
          tick={{ fontSize: 12, fill: '#3B82F6' }}
          axisLine={false}
          tickLine={false}
          tickFormatter={(v: number) => `$${(v / 1000).toFixed(1)}k`}
        />
        <YAxis
          yAxisId="sales"
          orientation="right"
          tick={{ fontSize: 12, fill: '#10B981' }}
          axisLine={false}
          tickLine={false}
          tickFormatter={(v: number) => `$${(v / 1000).toFixed(1)}k`}
        />
        <Tooltip
          formatter={(value: number, name: string) => [
            formatCurrency(value),
            name === 'spend' ? '花费' : '销售额',
          ]}
        />
        <Legend
          formatter={(value: string) => (value === 'spend' ? '花费' : '销售额')}
          wrapperStyle={{ fontSize: 12 }}
        />
        <Area yAxisId="spend" type="monotone" dataKey="spend" name="spend" stroke="#3B82F6" strokeWidth={2} fill="url(#campaignSpendGradient)" />
        <Line yAxisId="sales" type="monotone" dataKey="sales" name="sales" stroke="#10B981" strokeWidth={2} dot={false} />
      </ComposedChart>
    </ResponsiveContainer>
  );
}

export default CampaignTrendChart;
