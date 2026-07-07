// Recharts 7-day trend chart extracted from GoalDetailPage so it can be
// lazy-loaded (React.lazy) on demand. This keeps the heavy "charts" bundle out
// of the goal-detail page chunk until the performance tab's chart is rendered.

import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend,
} from 'recharts';

import { formatCurrency } from '../lib/utils';

export function GoalTrendChart({ data }: { data: any[] }) {
  return (
    <ResponsiveContainer width="100%" height="100%">
      <AreaChart data={data} margin={{ top: 5, right: 20, left: 10, bottom: 5 }}>
        <defs>
          <linearGradient id="colorSpend" x1="0" y1="0" x2="0" y2="1">
            <stop offset="5%" stopColor="#F97316" stopOpacity={0.15} />
            <stop offset="95%" stopColor="#F97316" stopOpacity={0} />
          </linearGradient>
          <linearGradient id="colorSales" x1="0" y1="0" x2="0" y2="1">
            <stop offset="5%" stopColor="#16A34A" stopOpacity={0.15} />
            <stop offset="95%" stopColor="#16A34A" stopOpacity={0} />
          </linearGradient>
        </defs>
        <CartesianGrid strokeDasharray="3 3" stroke="#E2E8F0" />
        <XAxis
          dataKey="date"
          tick={{ fontSize: 12, fill: '#94A3B8' }}
          axisLine={{ stroke: '#E2E8F0' }}
          tickLine={false}
        />
        <YAxis
          tick={{ fontSize: 12, fill: '#94A3B8' }}
          axisLine={{ stroke: '#E2E8F0' }}
          tickLine={false}
          tickFormatter={(v) => `$${v.toLocaleString()}`}
        />
        <Tooltip
          contentStyle={{
            backgroundColor: '#fff',
            border: '1px solid #E2E8F0',
            borderRadius: '8px',
            boxShadow: '0 4px 6px -1px rgb(0 0 0 / 0.1)',
            fontSize: '12px',
          }}
          formatter={(value: number, name: string) => [
            formatCurrency(value),
            name === 'spend' ? '花费' : '销售额',
          ]}
        />
        <Legend
          verticalAlign="top"
          height={36}
          formatter={(value) => (value === 'spend' ? '花费' : '销售额')}
        />
        <Area
          type="monotone"
          dataKey="spend"
          stroke="#F97316"
          strokeWidth={2}
          fill="url(#colorSpend)"
          dot={{ r: 4, fill: '#F97316', stroke: '#fff', strokeWidth: 2 }}
          activeDot={{ r: 6 }}
        />
        <Area
          type="monotone"
          dataKey="sales"
          stroke="#16A34A"
          strokeWidth={2}
          fill="url(#colorSales)"
          dot={{ r: 4, fill: '#16A34A', stroke: '#fff', strokeWidth: 2 }}
          activeDot={{ r: 6 }}
        />
      </AreaChart>
    </ResponsiveContainer>
  );
}

export default GoalTrendChart;
