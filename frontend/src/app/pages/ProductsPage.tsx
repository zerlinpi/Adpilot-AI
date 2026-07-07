import { useState, useMemo } from 'react';
import { Link } from 'react-router';
import { Plus, Package, Wand2, X, Loader2 } from 'lucide-react';
import { fetchProducts, createProduct } from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { useStoreId } from '../lib/useStoreId';
import { usePermissions } from '../lib/PermissionContext';
import { t } from '../i18n';
import { formatCurrency, formatPercent, formatNumber, cn } from '../lib/utils';
import { ErpEmptyState } from '../components/erp/ErpEmptyState';
import { ErpErrorState } from '../components/erp/ErpErrorState';
import { Dialog, DialogContent, DialogTitle } from '../components/ui/dialog';
import type { Product } from '../types';

function InventoryBadge({ inventory }: { inventory: number }) {
  if (inventory === 0) {
    return (
      <span className="inline-flex items-center px-2 py-0.5 text-xs font-medium rounded-full bg-red-100 text-red-700 border border-red-200">
        缺货
      </span>
    );
  }
  if (inventory < 200) {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 text-xs font-medium rounded-full bg-orange-100 text-orange-700 border border-orange-200">
        <span className="w-1.5 h-1.5 rounded-full bg-orange-500" />
        {formatNumber(inventory)}
      </span>
    );
  }
  return <span className="text-sm text-slate-700">{formatNumber(inventory)}</span>;
}

const colors = [
  'bg-indigo-500', 'bg-emerald-500', 'bg-violet-500',
  'bg-rose-500', 'bg-amber-500', 'bg-cyan-500',
];

// ─── Loading skeleton ─────────────────────────────────────────────────
function LoadingSkeleton() {
  return (
    <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-slate-100 bg-slate-50/60">
              <th className="text-left font-medium text-slate-500 px-4 py-3">产品</th>
              <th className="text-left font-medium text-slate-500 px-4 py-3">SKU</th>
              <th className="text-left font-medium text-slate-500 px-4 py-3">ASIN</th>
              <th className="text-right font-medium text-slate-500 px-4 py-3">价格</th>
              <th className="text-right font-medium text-slate-500 px-4 py-3">成本</th>
              <th className="text-right font-medium text-slate-500 px-4 py-3">利润率</th>
              <th className="text-right font-medium text-slate-500 px-4 py-3">库存</th>
              <th className="text-right font-medium text-slate-500 px-4 py-3">目标 ACoS</th>
              <th className="text-right font-medium text-slate-500 px-4 py-3">盈亏平衡 ACoS</th>
              <th className="text-center font-medium text-slate-500 px-4 py-3">状态</th>
              <th className="text-center font-medium text-slate-500 px-4 py-3">操作</th>
            </tr>
          </thead>
          <tbody>
            {Array.from({ length: 5 }).map((_, i) => (
              <tr key={i} className="border-b border-slate-50">
                <td className="px-4 py-3">
                  <div className="flex items-center gap-3">
                    <div className="w-9 h-9 rounded-lg bg-slate-200 animate-pulse" />
                    <div className="h-4 bg-slate-200 rounded w-32 animate-pulse" />
                  </div>
                </td>
                <td className="px-4 py-3"><div className="h-3 bg-slate-100 rounded w-16 animate-pulse" /></td>
                <td className="px-4 py-3"><div className="h-3 bg-slate-100 rounded w-20 animate-pulse" /></td>
                <td className="px-4 py-3 text-right"><div className="h-3 bg-slate-100 rounded w-14 ml-auto animate-pulse" /></td>
                <td className="px-4 py-3 text-right"><div className="h-3 bg-slate-100 rounded w-14 ml-auto animate-pulse" /></td>
                <td className="px-4 py-3 text-right"><div className="h-3 bg-slate-100 rounded w-12 ml-auto animate-pulse" /></td>
                <td className="px-4 py-3 text-right"><div className="h-3 bg-slate-100 rounded w-12 ml-auto animate-pulse" /></td>
                <td className="px-4 py-3 text-right"><div className="h-3 bg-slate-100 rounded w-12 ml-auto animate-pulse" /></td>
                <td className="px-4 py-3 text-right"><div className="h-3 bg-slate-100 rounded w-14 ml-auto animate-pulse" /></td>
                <td className="px-4 py-3 text-center"><div className="h-5 bg-slate-100 rounded-full w-16 mx-auto animate-pulse" /></td>
                <td className="px-4 py-3 text-center"><div className="h-6 bg-slate-100 rounded-lg w-20 mx-auto animate-pulse" /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export function ProductsPage() {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const { can } = usePermissions();
  const canCreate = can('product:create');

  // Create-product form state (wires the previously dead "添加产品" button to the
  // real POST /api/products endpoint; storeId + sku + name are required by the
  // backend ProductDto).
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState({ name: '', sku: '', asin: '', price: '', cost: '' });
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const productsQuery = useApiQuery(
    ['products', storeId],
    () => fetchProducts(storeId!),
    { enabled: !!storeId },
  );
  const products = Array.isArray(productsQuery.data) ? productsQuery.data : [];
  const loading = !!storeId && productsQuery.isLoading;
  const error = productsQuery.isError ? productsQuery.error?.message ?? '加载产品失败' : null;

  const activeCount = useMemo(
    () => products.filter((p) => p.status !== 'out_of_stock').length,
    [products],
  );

  function openForm() {
    setForm({ name: '', sku: '', asin: '', price: '', cost: '' });
    setFormError(null);
    setShowForm(true);
  }

  async function handleCreate() {
    if (!storeId) { setFormError('请先选择店铺'); return; }
    if (!form.name.trim()) { setFormError('请输入产品名称'); return; }
    if (!form.sku.trim()) { setFormError('请输入 SKU'); return; }
    setSaving(true);
    setFormError(null);
    try {
      await createProduct({
        storeId,
        name: form.name.trim(),
        sku: form.sku.trim(),
        asin: form.asin.trim() || undefined,
        price: form.price.trim() ? Number(form.price) : undefined,
        cost: form.cost.trim() ? Number(form.cost) : undefined,
      });
      setShowForm(false);
      await productsQuery.refetch();
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '创建产品失败');
    } finally {
      setSaving(false);
    }
  }

  // ─── Loading state ────────────────────────────────────────────────
  if (loading || storeLoading) {
    return (
      <div className="space-y-6">
        <div className="flex items-center justify-between">
          <div>
            <div className="h-8 bg-slate-200 rounded w-32 animate-pulse" />
            <div className="h-4 bg-slate-100 rounded w-40 mt-2 animate-pulse" />
          </div>
          <div className="h-10 bg-slate-200 rounded-lg w-32 animate-pulse" />
        </div>
        <LoadingSkeleton />
      </div>
    );
  }

  // ─── Error state ──────────────────────────────────────────────────
  if (error || storeError) {
    return (
      <div className="space-y-6">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-slate-900">{t('pages.products.title')}</h1>
          </div>
        </div>
        <ErpErrorState message={storeError || error || undefined} onRetry={() => productsQuery.refetch()} />
      </div>
    );
  }

  // ─── No store state ───────────────────────────────────────────────
  if (!storeId) {
    return (
      <div className="space-y-6">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-slate-900">{t('pages.products.title')}</h1>
          </div>
        </div>
        <div className="flex flex-col items-center justify-center py-20 text-center">
          <Package size={48} className="text-slate-300 mb-4" />
          <p className="text-lg font-medium text-slate-700">暂无可用店铺</p>
          <p className="text-sm text-slate-500 mt-1 max-w-md">
            请先在右上角切换/选择一个店铺，或前往「系统设置 → 店铺设置」创建并连接店铺后再使用本页。
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">{t('pages.products.title')}</h1>
          <p className="text-sm text-slate-500 mt-1">
            {products.length} 个产品 &middot; {activeCount} 个在售
          </p>
        </div>
        {canCreate && (
          <button
            onClick={openForm}
            className="inline-flex items-center gap-2 px-4 py-2.5 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors shadow-sm"
          >
            <Plus size={16} />
            添加产品
          </button>
        )}
      </div>

      {/* Empty state */}
      {products.length === 0 ? (
        <ErpEmptyState
          title="未找到产品"
          description="添加您的第一个产品以开始使用。"
          icon={<Package size={24} className="text-slate-400" />}
        />
      ) : (
        /* Table */
        <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-100 bg-slate-50/60">
                  <th className="text-left font-medium text-slate-500 px-4 py-3">产品</th>
                  <th className="text-left font-medium text-slate-500 px-4 py-3">SKU</th>
                  <th className="text-left font-medium text-slate-500 px-4 py-3">ASIN</th>
                  <th className="text-right font-medium text-slate-500 px-4 py-3">价格</th>
                  <th className="text-right font-medium text-slate-500 px-4 py-3">成本</th>
                  <th className="text-right font-medium text-slate-500 px-4 py-3">利润率</th>
                  <th className="text-right font-medium text-slate-500 px-4 py-3">库存</th>
                  <th className="text-right font-medium text-slate-500 px-4 py-3">目标 ACoS</th>
                  <th className="text-right font-medium text-slate-500 px-4 py-3">盈亏平衡 ACoS</th>
                  <th className="text-center font-medium text-slate-500 px-4 py-3">状态</th>
                  <th className="text-center font-medium text-slate-500 px-4 py-3">操作</th>
                </tr>
              </thead>
              <tbody>
                {products.map((product: Product, idx: number) => (
                  <tr
                    key={product.id}
                    className={cn(
                      'border-b border-slate-50 hover:bg-slate-50/50 transition-colors',
                      product.inventory === 0 && 'bg-red-50/30',
                      product.inventory > 0 && product.inventory < 200 && 'bg-orange-50/20',
                    )}
                  >
                    {/* Product cell */}
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-3">
                        <div
                          className={cn(
                            'w-9 h-9 rounded-lg flex items-center justify-center text-white text-sm font-semibold flex-shrink-0',
                            colors[idx % colors.length],
                          )}
                        >
                          {(product.name || '?').charAt(0)}
                        </div>
                        <span className="font-medium text-slate-900 truncate max-w-[220px]">
                          {product.name}
                        </span>
                      </div>
                    </td>

                    <td className="px-4 py-3 text-slate-600 font-mono text-xs">{product.sku}</td>
                    <td className="px-4 py-3 text-slate-600 font-mono text-xs">{product.asin}</td>
                    <td className="px-4 py-3 text-right text-slate-900 font-medium">{formatCurrency(product.price)}</td>
                    <td className="px-4 py-3 text-right text-slate-600">{formatCurrency(product.cost)}</td>
                    <td className="px-4 py-3 text-right text-slate-900">{formatPercent(product.grossMargin)}</td>
                    <td className="px-4 py-3 text-right">
                      <InventoryBadge inventory={product.inventory} />
                    </td>
                    <td className="px-4 py-3 text-right text-slate-700">{formatPercent(product.targetAcos)}</td>
                    <td className="px-4 py-3 text-right text-slate-700">{formatPercent(product.breakEvenAcos)}</td>
                    <td className="px-4 py-3 text-center">
                      <span
                        className={cn(
                          'inline-flex px-2.5 py-0.5 text-xs font-medium rounded-full border capitalize',
                          product.status === 'active' && 'bg-emerald-100 text-emerald-700 border-emerald-200',
                          product.status === 'paused' && 'bg-slate-100 text-slate-500 border-slate-200',
                          product.status === 'out_of_stock' && 'bg-red-100 text-red-700 border-red-200',
                        )}
                      >
                        {product.status === 'out_of_stock' ? '缺货' : product.status}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-center">
                      <Link
                        to={`/products/${product.id}/listing-ai`}
                        className="inline-flex items-center gap-1.5 px-2.5 py-1 text-xs font-medium text-indigo-600 bg-indigo-50 border border-indigo-100 rounded-lg hover:bg-indigo-100 transition-colors"
                      >
                        <Wand2 size={12} />
                        Listing AI
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Create product modal */}
      {showForm && (
        <Dialog open onOpenChange={(o) => { if (!o && !saving) setShowForm(false); }}>
          <DialogContent className="block gap-0 w-full sm:max-w-md rounded-xl border-0 bg-white p-6 shadow-xl">
            <div className="flex items-center justify-between mb-4">
              <DialogTitle className="text-base font-semibold text-slate-900">添加产品</DialogTitle>
            </div>

            {formError && (
              <div className="bg-red-50 border border-red-200 rounded-lg p-2.5 mb-4 text-sm text-red-700">{formError}</div>
            )}

            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">产品名称 *</label>
                <input
                  type="text"
                  value={form.name}
                  onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
                  placeholder="例如：无线蓝牙耳机"
                  className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
                />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">SKU *</label>
                  <input
                    type="text"
                    value={form.sku}
                    onChange={(e) => setForm((f) => ({ ...f, sku: e.target.value }))}
                    placeholder="例如：SKU-001"
                    className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">ASIN（选填）</label>
                  <input
                    type="text"
                    value={form.asin}
                    onChange={(e) => setForm((f) => ({ ...f, asin: e.target.value }))}
                    placeholder="例如：B0XXXXXXXX"
                    className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">价格（选填）</label>
                  <input
                    type="number"
                    min="0"
                    step="0.01"
                    value={form.price}
                    onChange={(e) => setForm((f) => ({ ...f, price: e.target.value }))}
                    placeholder="0.00"
                    className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">成本（选填）</label>
                  <input
                    type="number"
                    min="0"
                    step="0.01"
                    value={form.cost}
                    onChange={(e) => setForm((f) => ({ ...f, cost: e.target.value }))}
                    placeholder="0.00"
                    className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
                  />
                </div>
              </div>
            </div>

            <div className="flex items-center justify-end gap-3 mt-6">
              <button onClick={() => setShowForm(false)} disabled={saving} className="px-4 py-2 text-sm font-medium text-slate-600 hover:text-slate-800 disabled:opacity-50">
                取消
              </button>
              <button
                onClick={handleCreate}
                disabled={saving}
                className="inline-flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors shadow-sm disabled:opacity-60"
              >
                {saving ? <Loader2 size={15} className="animate-spin" /> : <Plus size={15} />}
                创建产品
              </button>
            </div>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}
