import { useParams, Link } from 'react-router';
import {
  ArrowLeft, Truck, Package, FileCheck, MapPin, AlertTriangle,
  DollarSign, Boxes, RefreshCw,
} from 'lucide-react';
import {
  fetchShipment,
  fetchShipmentLegs,
  fetchCartonSpecs,
  fetchCustomsClearance,
  fetchTrackingEvents,
  fetchShipmentExceptions,
  fetchCostChain,
  fetchFbaFields,
  type ShipmentLeg,
  type CartonSpec,
  type TrackingEvent,
  type CostComponent,
} from '../lib/api';
import { qk } from '../lib/queryKeys';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { useStoreContext } from '../lib/StoreContext';
import { getChannelCapability } from '../lib/channelCapabilities';
import { cn, formatCurrency, formatDate, formatDateTime } from '../lib/utils';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpErrorState } from '../components/erp/ErpErrorState';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';
import { ErpStatusBadge } from '../components/erp/ErpStatusBadge';

// ─── Helpers ────────────────────────────────────────────────────────

const LEG_TYPE_LABELS: Record<string, string> = {
  first_leg: '头程',
  head_haul: '头程',
  last_leg: '尾程',
  forwarder: '货代',
  ocean_freight: '海运',
  air_freight: '空运',
  customs: '清关',
};

const CUSTOMS_STATUS_LABELS: Record<string, string> = {
  'not-started': '未开始',
  declared: '已申报',
  'in-review': '审核中',
  cleared: '已清关',
  held: '已扣留',
};

const EXCEPTION_TYPE_LABELS: Record<string, string> = {
  delay: '延误',
  damage: '货损',
  customs_hold: '清关扣留',
};

// ─── Section shell ──────────────────────────────────────────────────

interface SectionProps {
  title: string;
  icon: React.ReactNode;
  loading?: boolean;
  error?: string | null;
  onRetry?: () => void;
  /** When true the section renders its empty placeholder instead of children. */
  empty?: boolean;
  emptyText?: string;
  extra?: React.ReactNode;
  children?: React.ReactNode;
}

function Section({ title, icon, loading, error, onRetry, empty, emptyText, extra, children }: SectionProps) {
  return (
    <section className="bg-white rounded-xl border border-slate-200">
      <header className="flex items-center justify-between px-4 py-3 border-b border-slate-100">
        <div className="flex items-center gap-2 text-slate-800">
          <span className="text-slate-400">{icon}</span>
          <h2 className="text-sm font-semibold">{title}</h2>
        </div>
        {extra}
      </header>
      <div className="p-4">
        {loading ? (
          <div className="h-16 flex items-center justify-center text-sm text-slate-400">
            <RefreshCw size={14} className="animate-spin mr-2" /> 加载中...
          </div>
        ) : error ? (
          <ErpErrorState message={error} onRetry={onRetry} />
        ) : empty ? (
          <p className="py-6 text-center text-sm text-slate-400">{emptyText}</p>
        ) : (
          children
        )}
      </div>
    </section>
  );
}

// ─── Main page ──────────────────────────────────────────────────────

export function ShipmentDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const { stores } = useStoreContext();

  // Header (drives the page-level loading / error / not-found state).
  const shipmentQ = useApiQuery(qk.shipmentDetail(id), () => fetchShipment(id), { enabled: !!id });
  const shipmentStore = stores.find((store) => store.id === shipmentQ.data?.storeId);
  const isAmazonShipment = getChannelCapability(shipmentStore?.platform).platform === 'amazon';

  // Section queries — each manages its own loading/error/empty state so one
  // failing section never blanks the rest of the page.
  const legsQ = useApiQuery(qk.shipmentLegs(id), () => fetchShipmentLegs(id), { enabled: !!id });
  const cartonsQ = useApiQuery(qk.shipmentCartons(id), () => fetchCartonSpecs(id), { enabled: !!id });
  const customsQ = useApiQuery(qk.shipmentCustoms(id), () => fetchCustomsClearance(id), { enabled: !!id });
  const trackingQ = useApiQuery(qk.shipmentTracking(id), () => fetchTrackingEvents(id), { enabled: !!id });
  const exceptionsQ = useApiQuery(qk.shipmentExceptions(id), () => fetchShipmentExceptions(id), { enabled: !!id });
  const costChainQ = useApiQuery(qk.shipmentCostChain(id), () => fetchCostChain(id), { enabled: !!id });
  const fbaQ = useApiQuery(qk.shipmentFba(id), () => fetchFbaFields(id), { enabled: !!id && isAmazonShipment });

  const backLink = (
    <Link
      to="/fba-shipments"
      className="inline-flex items-center gap-1.5 text-sm text-slate-500 hover:text-slate-700 transition-colors"
    >
      <ArrowLeft size={15} /> 返回货件列表
    </Link>
  );

  // ─── Page-level loading / error ───────────────────────────────────
  if (shipmentQ.isLoading) {
    return (
      <div className="space-y-4">
        {backLink}
        <ErpPageHeader title="货件详情" description="加载中..." />
        <ErpLoadingSkeleton rows={6} />
      </div>
    );
  }

  if (shipmentQ.isError) {
    return (
      <div className="space-y-4">
        {backLink}
        <ErpPageHeader title="货件详情" />
        <div className="bg-white rounded-xl border border-slate-200 p-8">
          <ErpErrorState message={shipmentQ.error?.message} onRetry={() => shipmentQ.refetch()} />
        </div>
      </div>
    );
  }

  const shipment = shipmentQ.data;

  // ─── Carton totals — computed on the frontend (Req 5.3, 5.4) ───────
  const cartons: CartonSpec[] = cartonsQ.data ?? [];
  const totalBoxCount = cartons.reduce((sum, c) => sum + (c.boxCount ?? 0), 0);
  const totalUnitQuantity = cartons.reduce(
    (sum, c) => sum + (c.unitsPerBox ?? 0) * (c.boxCount ?? 0),
    0,
  );

  // ─── Legs ordered by sequence (Req 4.4) ───────────────────────────
  const legs: ShipmentLeg[] = [...(legsQ.data ?? [])].sort(
    (a, b) => (a.sequenceNo ?? 0) - (b.sequenceNo ?? 0),
  );

  // ─── Tracking events most-recent first (Req 8.7) ───────────────────
  // The backend already returns newest-first; we keep that order as supplied.
  const events: TrackingEvent[] = trackingQ.data ?? [];

  const customs = customsQ.data;
  const exceptions = exceptionsQ.data ?? [];
  const openExceptions = exceptions.filter((e) => e.resolutionState !== 'resolved');
  const costChain = costChainQ.data;
  const fba = fbaQ.data;
  const reportingCurrency = costChain?.reportingCurrency || shipment?.currency || 'USD';

  return (
    <div className="space-y-4">
      {backLink}

      <ErpPageHeader
        title={shipment?.shipmentId || '货件详情'}
        description={shipment?.trackingNumber ? `追踪号 ${shipment.trackingNumber}` : '物流详情'}
        actions={
          openExceptions.length > 0 ? (
            <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-red-50 text-red-700 text-xs font-medium border border-red-200">
              <AlertTriangle size={14} />
              {openExceptions.length} 个未处理异常
            </span>
          ) : null
        }
      />

      {/* ─── Summary card ─── */}
      <div className="bg-white rounded-xl border border-slate-200 p-4 grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">
        <div>
          <div className="text-slate-400 text-xs mb-1">状态</div>
          <ErpStatusBadge status={shipment?.status || 'pending'} />
        </div>
        <div>
          <div className="text-slate-400 text-xs mb-1">承运商</div>
          <div className="text-slate-700">{shipment?.carrier || '-'}</div>
        </div>
        <div>
          <div className="text-slate-400 text-xs mb-1">发货日期</div>
          <div className="text-slate-700">{formatDate(shipment?.shipDate)}</div>
        </div>
        <div>
          <div className="text-slate-400 text-xs mb-1">预计到达</div>
          <div className="text-slate-700">{formatDate(shipment?.estimatedDeliveryDate)}</div>
        </div>
      </div>

      {/* ─── Transport legs (Req 4.4, 4.8) ─── */}
      <Section
        title="运输路径"
        icon={<Truck size={16} />}
        loading={legsQ.isLoading}
        error={legsQ.isError ? legsQ.error?.message : null}
        onRetry={() => legsQ.refetch()}
        empty={legs.length === 0}
        emptyText="暂无运输段，添加头程/尾程以记录完整运输路径。"
      >
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-slate-500 border-b border-slate-100">
                <th className="py-2 pr-4 font-medium">序号</th>
                <th className="py-2 pr-4 font-medium">类型</th>
                <th className="py-2 pr-4 font-medium">承运商</th>
                <th className="py-2 pr-4 font-medium">出发日期</th>
                <th className="py-2 pr-4 font-medium">到达日期</th>
                <th className="py-2 pr-4 font-medium text-right">运段费用</th>
              </tr>
            </thead>
            <tbody>
              {legs.map((leg) => (
                <tr key={leg.id} className="border-b border-slate-50 last:border-0">
                  <td className="py-2 pr-4 text-slate-700">{leg.sequenceNo}</td>
                  <td className="py-2 pr-4 text-slate-700">{LEG_TYPE_LABELS[leg.legType] || leg.legType}</td>
                  <td className="py-2 pr-4 text-slate-700">{leg.carrierName || '-'}</td>
                  <td className="py-2 pr-4 text-slate-600">{formatDate(leg.departureDate)}</td>
                  <td className="py-2 pr-4 text-slate-600">{formatDate(leg.arrivalDate)}</td>
                  <td className="py-2 pr-4 text-right text-slate-700">
                    {leg.legCost != null ? formatCurrency(leg.legCost, reportingCurrency) : '-'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>

      {/* ─── Carton specs + computed totals (Req 5.3, 5.4) ─── */}
      <Section
        title="箱规"
        icon={<Boxes size={16} />}
        loading={cartonsQ.isLoading}
        error={cartonsQ.isError ? cartonsQ.error?.message : null}
        onRetry={() => cartonsQ.refetch()}
        extra={
          <div className="flex items-center gap-4 text-xs text-slate-500">
            <span>总箱数 <b className="text-slate-800">{totalBoxCount}</b></span>
            <span>总数量 <b className="text-slate-800">{totalUnitQuantity}</b></span>
          </div>
        }
      >
        {cartons.length === 0 ? (
          <p className="py-6 text-center text-sm text-slate-400">
            暂无箱规记录。总箱数 0，总数量 0。
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-slate-500 border-b border-slate-100">
                  <th className="py-2 pr-4 font-medium">长 (cm)</th>
                  <th className="py-2 pr-4 font-medium">宽 (cm)</th>
                  <th className="py-2 pr-4 font-medium">高 (cm)</th>
                  <th className="py-2 pr-4 font-medium">重量 (kg)</th>
                  <th className="py-2 pr-4 font-medium text-right">每箱数量</th>
                  <th className="py-2 pr-4 font-medium text-right">箱数</th>
                </tr>
              </thead>
              <tbody>
                {cartons.map((c) => (
                  <tr key={c.id} className="border-b border-slate-50 last:border-0">
                    <td className="py-2 pr-4 text-slate-700">{c.boxLengthCm ?? '-'}</td>
                    <td className="py-2 pr-4 text-slate-700">{c.boxWidthCm ?? '-'}</td>
                    <td className="py-2 pr-4 text-slate-700">{c.boxHeightCm ?? '-'}</td>
                    <td className="py-2 pr-4 text-slate-700">{c.boxWeightKg ?? '-'}</td>
                    <td className="py-2 pr-4 text-right text-slate-700">{c.unitsPerBox ?? '-'}</td>
                    <td className="py-2 pr-4 text-right text-slate-700">{c.boxCount ?? '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>

      {/* ─── Customs clearance (Req 7.5, 7.6) ─── */}
      <Section
        title="清关"
        icon={<FileCheck size={16} />}
        loading={customsQ.isLoading}
        error={customsQ.isError ? customsQ.error?.message : null}
        onRetry={() => customsQ.refetch()}
      >
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-sm">
          <div>
            <div className="text-slate-400 text-xs mb-1">清关状态</div>
            <div className="text-slate-800 font-medium">
              {CUSTOMS_STATUS_LABELS[customs?.clearanceStatus || 'not-started'] || customs?.clearanceStatus || '未开始'}
            </div>
          </div>
          <div>
            <div className="text-slate-400 text-xs mb-1">申报单号</div>
            <div className="text-slate-700">{customs?.declarationRef || '-'}</div>
          </div>
          <div>
            <div className="text-slate-400 text-xs mb-1">关税及税费</div>
            <div className="text-slate-700">
              {customs?.dutiesTaxes != null
                ? formatCurrency(customs.dutiesTaxes, reportingCurrency)
                : formatCurrency(0, reportingCurrency)}
            </div>
          </div>
        </div>
      </Section>

      {/* ─── Tracking trajectory (Req 8.7, 8.8) ─── */}
      <Section
        title="物流轨迹"
        icon={<MapPin size={16} />}
        loading={trackingQ.isLoading}
        error={trackingQ.isError ? trackingQ.error?.message : null}
        onRetry={() => trackingQ.refetch()}
        empty={events.length === 0}
        emptyText="暂无物流轨迹记录。"
      >
        <ol className="relative border-l border-slate-200 ml-2">
          {events.map((ev) => (
            <li key={ev.id} className="mb-4 ml-4 last:mb-0">
              <span className="absolute -left-1.5 mt-1.5 w-3 h-3 rounded-full bg-indigo-500 border-2 border-white" />
              <div className="text-xs text-slate-400">{formatDateTime(ev.eventTime)}</div>
              <div className="text-sm text-slate-700">{ev.description || '-'}</div>
            </li>
          ))}
        </ol>
      </Section>

      {/* ─── Exceptions (Req 9.6) ─── */}
      <Section
        title="异常"
        icon={<AlertTriangle size={16} />}
        loading={exceptionsQ.isLoading}
        error={exceptionsQ.isError ? exceptionsQ.error?.message : null}
        onRetry={() => exceptionsQ.refetch()}
        empty={exceptions.length === 0}
        emptyText="暂无异常记录。"
      >
        <div className="space-y-2">
          {exceptions.map((ex) => (
            <div
              key={ex.id}
              className="flex items-start justify-between gap-3 p-3 rounded-lg border border-slate-100 bg-slate-50/50"
            >
              <div>
                <div className="text-sm font-medium text-slate-800">
                  {EXCEPTION_TYPE_LABELS[ex.exceptionType || ''] || ex.exceptionType || '异常'}
                </div>
                {ex.description && <div className="text-xs text-slate-500 mt-0.5">{ex.description}</div>}
              </div>
              <ErpStatusBadge status={ex.resolutionState || 'open'} />
            </div>
          ))}
        </div>
      </Section>

      {/* ─── Cost chain itemized + total (Req 10.3) ─── */}
      <Section
        title="费用链路"
        icon={<DollarSign size={16} />}
        loading={costChainQ.isLoading}
        error={costChainQ.isError ? costChainQ.error?.message : null}
        onRetry={() => costChainQ.refetch()}
        extra={
          <span className="text-xs text-slate-500">
            合计 <b className="text-slate-800">{formatCurrency(costChain?.totalLandedCost ?? 0, reportingCurrency)}</b>
          </span>
        }
      >
        <CostChainBody
          legCosts={costChain?.legCosts ?? []}
          handlingCosts={costChain?.handlingCosts ?? []}
          customsDutiesTaxes={costChain?.customsDutiesTaxes ?? 0}
          total={costChain?.totalLandedCost ?? 0}
          currency={reportingCurrency}
          legs={legs}
        />
      </Section>

      {/* ─── FBA fields + line items (Req 16.4) ─── */}
      {isAmazonShipment && (
        <Section
          title="FBA 信息"
          icon={<Package size={16} />}
          loading={fbaQ.isLoading}
          error={fbaQ.isError ? fbaQ.error?.message : null}
          onRetry={() => fbaQ.refetch()}
        >
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-sm mb-4">
            <div>
              <div className="text-slate-400 text-xs mb-1">FBA 货件号</div>
              <div className="text-slate-700">{fba?.fbaShipmentId || '-'}</div>
            </div>
            <div>
              <div className="text-slate-400 text-xs mb-1">Amazon 状态</div>
              <div className="text-slate-700">{fba?.amazonShipmentStatus || '-'}</div>
            </div>
            <div>
              <div className="text-slate-400 text-xs mb-1">目的运营中心 (FC)</div>
              <div className="text-slate-700">{fba?.destinationFcCode || '-'}</div>
            </div>
          </div>
          {fba?.lineItems && fba.lineItems.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-slate-500 border-b border-slate-100">
                    <th className="py-2 pr-4 font-medium">SKU</th>
                    <th className="py-2 pr-4 font-medium">MSKU</th>
                    <th className="py-2 pr-4 font-medium">ASIN</th>
                    <th className="py-2 pr-4 font-medium text-right">数量</th>
                  </tr>
                </thead>
                <tbody>
                  {fba.lineItems.map((li, idx) => (
                    <tr key={li.id || idx} className="border-b border-slate-50 last:border-0">
                      <td className="py-2 pr-4 text-slate-700">{li.sku || '-'}</td>
                      <td className="py-2 pr-4 text-slate-700">{li.msku || '-'}</td>
                      <td className="py-2 pr-4 text-slate-700 font-mono text-xs">{li.asin || '-'}</td>
                      <td className="py-2 pr-4 text-right text-slate-700">{li.quantity ?? '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="py-4 text-center text-sm text-slate-400">暂无 FBA 行项目。</p>
          )}
        </Section>
      )}
    </div>
  );
}

// ─── Cost chain body ────────────────────────────────────────────────

interface CostChainBodyProps {
  legCosts: CostComponent[];
  handlingCosts: CostComponent[];
  customsDutiesTaxes: number;
  total: number;
  currency: string;
  legs: ShipmentLeg[];
}

function CostChainBody({ legCosts, handlingCosts, customsDutiesTaxes, total, currency, legs }: CostChainBodyProps) {
  const legLabel = (legId?: string) => {
    if (!legId) return '运段';
    const leg = legs.find((l) => l.id === legId);
    if (!leg) return '运段';
    return `${LEG_TYPE_LABELS[leg.legType] || leg.legType} (#${leg.sequenceNo})`;
  };

  const renderOriginal = (c: CostComponent) =>
    c.originalAmount != null && c.originalCurrency
      ? <span className="text-xs text-slate-400 ml-2">({c.originalCurrency} {c.originalAmount})</span>
      : null;

  return (
    <div className="text-sm">
      <table className="w-full">
        <tbody>
          {legCosts.map((c, idx) => (
            <tr key={`leg-${c.legId || idx}`} className="border-b border-slate-50">
              <td className="py-2 text-slate-600">运段费用 — {legLabel(c.legId)}{renderOriginal(c)}</td>
              <td className="py-2 text-right text-slate-700">{formatCurrency(c.amount ?? 0, currency)}</td>
            </tr>
          ))}
          <tr className="border-b border-slate-50">
            <td className="py-2 text-slate-600">关税及税费</td>
            <td className="py-2 text-right text-slate-700">{formatCurrency(customsDutiesTaxes ?? 0, currency)}</td>
          </tr>
          {handlingCosts.map((c, idx) => (
            <tr key={`handling-${c.id || idx}`} className="border-b border-slate-50">
              <td className="py-2 text-slate-600">操作费用{renderOriginal(c)}</td>
              <td className="py-2 text-right text-slate-700">{formatCurrency(c.amount ?? 0, currency)}</td>
            </tr>
          ))}
          <tr>
            <td className={cn('py-2.5 font-semibold text-slate-800')}>合计落地物流成本</td>
            <td className="py-2.5 text-right font-semibold text-indigo-700">{formatCurrency(total ?? 0, currency)}</td>
          </tr>
        </tbody>
      </table>
    </div>
  );
}
