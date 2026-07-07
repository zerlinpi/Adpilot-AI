import { useState } from 'react';
import { Plus, Image as ImageIcon, Video, Search, UploadCloud, X } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import {
  fetchCreativeAssets,
  uploadCreativeAsset,
  type CreativeAsset,
  type CreativeAssetUploadInput,
} from '../lib/api';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { useStoreId } from '../lib/useStoreId';
import { cn } from '../lib/utils';
import { Dialog, DialogContent, DialogTitle } from '../components/ui/dialog';

// ─── Asset type labels (SparkX creative categories) ──────────────────
const assetTypeOptions: { value: string; label: string }[] = [
  { value: 'lifestyle', label: '生活方式图' },
  { value: 'scene', label: '场景图' },
  { value: 'hd_group', label: '高清图组' },
  { value: 'marketing', label: '营销宣传图' },
];

const assetTypeLabelMap: Record<string, string> = Object.fromEntries(
  assetTypeOptions.map((o) => [o.value, o.label]),
);

function assetTypeBadge(type: string): string {
  const styles: Record<string, string> = {
    lifestyle: 'bg-emerald-100 text-emerald-700 border-emerald-200',
    scene: 'bg-blue-100 text-blue-700 border-blue-200',
    hd_group: 'bg-purple-100 text-purple-700 border-purple-200',
    marketing: 'bg-amber-100 text-amber-700 border-amber-200',
  };
  return styles[type] || 'bg-slate-100 text-slate-600 border-slate-200';
}

// ─── Loading skeleton ────────────────────────────────────────────────
function AssetsSkeleton() {
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <div className="h-8 w-40 bg-slate-200 rounded animate-pulse" />
          <div className="h-4 w-64 bg-slate-100 rounded animate-pulse mt-2" />
        </div>
        <div className="h-10 w-32 bg-slate-200 rounded-lg animate-pulse" />
      </div>
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-4">
        {Array.from({ length: 8 }).map((_, i) => (
          <div key={i} className="h-48 bg-slate-100 rounded-xl animate-pulse" />
        ))}
      </div>
    </div>
  );
}

// ─── Upload Modal ────────────────────────────────────────────────────
function UploadModal({
  storeId,
  onClose,
  onUploaded,
}: {
  storeId: string;
  onClose: () => void;
  onUploaded: (created: CreativeAsset) => void;
}) {
  const [file, setFile] = useState<File | null>(null);
  const [name, setName] = useState('');
  const [assetType, setAssetType] = useState('lifestyle');
  const [asin, setAsin] = useState('');
  const [tags, setTags] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFieldError(null);
    setFormError(null);

    if (!file) {
      setFieldError('请选择要上传的图片或视频文件');
      return;
    }
    const trimmedName = name.trim();
    if (!trimmedName) {
      setFieldError('请输入素材名称');
      return;
    }
    if (trimmedName.length > 255) {
      setFieldError('素材名称不能超过 255 个字符');
      return;
    }

    const payload: CreativeAssetUploadInput = {
      file,
      storeId,
      name: trimmedName,
      assetType,
      asin: asin.trim() || undefined,
      tags: tags
        .split(',')
        .map((t) => t.trim())
        .filter((t) => t.length > 0),
    };

    try {
      setSubmitting(true);
      const created = await uploadCreativeAsset(payload);
      onUploaded(created);
    } catch (err: any) {
      setFormError(err?.message || '上传素材失败');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open onOpenChange={(o) => { if (!o && !submitting) onClose(); }}>
      <DialogContent className="block gap-0 w-full sm:max-w-lg rounded-xl border-0 bg-white p-0 shadow-xl">
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
          <DialogTitle className="text-lg font-semibold text-slate-900">上传创意素材</DialogTitle>
        </div>

        <form onSubmit={handleSubmit} className="px-5 py-4 space-y-4">
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">文件</label>
            <label className="flex flex-col items-center justify-center gap-2 px-3 py-6 rounded-lg border-2 border-dashed border-slate-200 cursor-pointer hover:border-blue-300 hover:bg-blue-50/40 transition-colors">
              <UploadCloud size={24} className="text-slate-400" />
              <span className="text-sm text-slate-500">
                {file ? file.name : '点击选择图片或视频'}
              </span>
              <input
                type="file"
                accept="image/*,video/*"
                className="hidden"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              />
            </label>
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">素材名称</label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="例如：夏季新品主图"
              className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1">素材类型</label>
              <select
                value={assetType}
                onChange={(e) => setAssetType(e.target.value)}
                className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
              >
                {assetTypeOptions.map((o) => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1">ASIN（可选）</label>
              <input
                type="text"
                value={asin}
                onChange={(e) => setAsin(e.target.value)}
                placeholder="B0XXXXXXXX"
                className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
              />
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">标签（可选，逗号分隔）</label>
            <input
              type="text"
              value={tags}
              onChange={(e) => setTags(e.target.value)}
              placeholder="例如：夏季, 主图, 高清"
              className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
            />
          </div>

          {fieldError && <p className="text-sm text-red-500">{fieldError}</p>}
          {formError && <p className="text-sm text-red-500">{formError}</p>}

          <div className="flex items-center justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 rounded-lg border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50"
            >
              取消
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:opacity-60"
            >
              {submitting ? '上传中...' : '上传'}
            </button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}

// ─── Asset card ──────────────────────────────────────────────────────
function AssetCard({ asset }: { asset: CreativeAsset }) {
  const isVideo = asset.mediaKind === 'video';
  return (
    <div className="bg-white rounded-xl border border-slate-200 overflow-hidden hover:shadow-md transition-shadow">
      <div className="aspect-video bg-slate-50 flex items-center justify-center overflow-hidden">
        {isVideo ? (
          <Video size={36} className="text-slate-300" />
        ) : asset.storageUrl ? (
          <img
            src={asset.storageUrl}
            alt={asset.name}
            className="w-full h-full object-cover"
            onError={(e) => {
              (e.currentTarget as HTMLImageElement).style.display = 'none';
            }}
          />
        ) : (
          <ImageIcon size={36} className="text-slate-300" />
        )}
      </div>
      <div className="p-3 space-y-2">
        <div className="flex items-start justify-between gap-2">
          <p className="text-sm font-medium text-slate-900 truncate" title={asset.name}>{asset.name}</p>
          <span
            className={cn(
              'inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-medium border flex-shrink-0',
              assetTypeBadge(asset.assetType),
            )}
          >
            {assetTypeLabelMap[asset.assetType] || asset.assetType}
          </span>
        </div>
        {asset.asin && <p className="text-xs text-slate-400">ASIN: {asset.asin}</p>}
        {asset.tags && asset.tags.length > 0 && (
          <div className="flex flex-wrap gap-1">
            {asset.tags.map((tag) => (
              <span key={tag} className="px-1.5 py-0.5 rounded bg-slate-100 text-[10px] text-slate-500">
                {tag}
              </span>
            ))}
          </div>
        )}
        <div className="flex items-center justify-between text-[11px] text-slate-400 pt-1">
          <span>{asset.creator || '—'}</span>
          <span>{asset.createdAt || ''}</span>
        </div>
      </div>
    </div>
  );
}

// ─── Main Creative Assets Page ───────────────────────────────────────
export function CreativeAssetsPage() {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const queryClient = useQueryClient();
  const [showUpload, setShowUpload] = useState(false);

  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [typeFilter, setTypeFilter] = useState('');

  const assetsKey = ['creative-assets', storeId, { search, typeFilter }] as const;
  const assetsQuery = useApiQuery<CreativeAsset[]>(
    assetsKey,
    () => fetchCreativeAssets(storeId!, {
      search: search || undefined,
      assetType: typeFilter || undefined,
    }).then((list) => list ?? []),
    { enabled: !!storeId, placeholderData: (prev) => prev },
  );
  const assets = assetsQuery.data ?? [];
  const loading = !!storeId && assetsQuery.isLoading;
  const error = assetsQuery.isError ? assetsQuery.error?.message ?? '加载创意素材失败' : null;
  const loadData = () => assetsQuery.refetch();

  function handleSearchSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSearch(searchInput.trim());
  }

  if ((loading && assets.length === 0) || storeLoading) return <AssetsSkeleton />;

  if (error || storeError) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <ImageIcon size={48} className="text-red-300 mb-4" />
        <p className="text-lg font-medium text-slate-700">出错了</p>
        <p className="text-sm text-slate-500 mt-1 max-w-md">{storeError || error}</p>
        <button
          onClick={loadData}
          className="mt-4 px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition-colors"
        >
          重试
        </button>
      </div>
    );
  }

  if (!storeId) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <ImageIcon size={48} className="text-slate-300 mb-4" />
        <p className="text-lg font-medium text-slate-700">暂无可用店铺</p>
        <p className="text-sm text-slate-500 mt-1 max-w-md">
          请先在右上角切换/选择一个店铺，或前往「系统设置 → 店铺设置」创建并连接店铺后再使用本页。
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">创意素材</h1>
          <p className="text-sm text-slate-500 mt-1">管理可复用的广告图片与视频素材</p>
        </div>
        <button
          onClick={() => setShowUpload(true)}
          disabled={!storeId}
          className="inline-flex items-center gap-2 px-4 py-2.5 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition-colors shadow-sm disabled:opacity-60 disabled:cursor-not-allowed"
        >
          <Plus size={16} />
          上传素材
        </button>
      </div>

      {/* Search + type filter */}
      <div className="flex flex-col sm:flex-row gap-3">
        <form onSubmit={handleSearchSubmit} className="flex-1 flex items-center gap-2 bg-white rounded-lg border border-slate-200 px-3 h-10">
          <Search size={16} className="text-slate-400 flex-shrink-0" />
          <input
            type="text"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            placeholder="按名称、标签、ASIN 或创建人搜索"
            className="flex-1 text-sm outline-none bg-transparent"
          />
          {searchInput && (
            <button
              type="button"
              onClick={() => { setSearchInput(''); setSearch(''); }}
              className="text-slate-400 hover:text-slate-600"
              aria-label="清除搜索"
            >
              <X size={15} />
            </button>
          )}
        </form>
        <select
          value={typeFilter}
          onChange={(e) => setTypeFilter(e.target.value)}
          className="h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100 bg-white"
        >
          <option value="">全部类型</option>
          {assetTypeOptions.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
      </div>

      {/* Asset grid / Empty state */}
      {assets.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-20 text-center">
          <ImageIcon size={48} className="text-slate-300 mb-4" />
          <p className="text-lg font-medium text-slate-500">
            {search || typeFilter ? '未找到匹配的素材' : '暂无创意素材'}
          </p>
          <p className="text-sm text-slate-400 mt-1">
            {search || typeFilter ? '试试调整搜索条件' : '点击「上传素材」开始构建素材库'}
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-4">
          {assets.map((asset) => (
            <AssetCard key={asset.id} asset={asset} />
          ))}
        </div>
      )}

      {showUpload && storeId && (
        <UploadModal
          storeId={storeId}
          onClose={() => setShowUpload(false)}
          onUploaded={(created) => {
            setShowUpload(false);
            // Reflect the new asset without a full reload (Req 29.2).
            queryClient.setQueryData<CreativeAsset[]>(assetsKey, (prev) => [created, ...(prev ?? [])]);
          }}
        />
      )}
    </div>
  );
}
