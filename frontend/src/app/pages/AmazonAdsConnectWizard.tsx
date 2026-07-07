import { useState, useEffect, useCallback } from 'react';
import {
  Loader2, ExternalLink, Copy, Check, CheckCircle2, AlertTriangle, ArrowRight, ArrowLeft,
} from 'lucide-react';
import { cn } from '../lib/utils';
import {
  getAmazonAdsAuthorizeUrl, amazonAdsCallback, bindAmazonAdsProfile,
  type AmazonAdsProfile,
} from '../lib/api';
import { Dialog, DialogContent, DialogTitle } from '../components/ui/dialog';

// Amazon regions and the marketplaces each covers, shown in step 1 like the
// SparkX flow (北美洲 / 欧洲 / 远东).
const REGIONS: { key: string; label: string; countries: string }[] = [
  { key: 'NA', label: '北美洲 (NA)', countries: '美国 / 墨西哥 / 加拿大 / 巴西' },
  { key: 'EU', label: '欧洲 (EU)', countries: '英国 / 德国 / 法国 / 意大利 / 西班牙 / 荷兰 / 瑞典 / 波兰 / 比利时 / 阿联酋 / 沙特 / 埃及 / 土耳其 / 印度' },
  { key: 'FE', label: '远东 (FE)', countries: '日本 / 澳大利亚 / 新加坡' },
];

interface StoreOption { id: string; name: string }


interface Props {
  open: boolean;
  stores: StoreOption[];
  /** Pre-selected store (e.g. the page's current store). */
  defaultStoreId?: string;
  /** When the wizard is opened to resume an OAuth redirect, the code+state. */
  resumeCode?: string | null;
  resumeState?: string | null;
  onClose: () => void;
  onConnected: () => void;
}

type Step = 'store' | 'authorize' | 'select' | 'done';

export function AmazonAdsConnectWizard({
  open, stores, defaultStoreId, resumeCode, resumeState, onClose, onConnected,
}: Props) {
  const [step, setStep] = useState<Step>('store');
  const [region, setRegion] = useState('NA');
  const [storeId, setStoreId] = useState(defaultStoreId || '');
  const [authorizeUrl, setAuthorizeUrl] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [callbackUrl, setCallbackUrl] = useState('');
  const [profiles, setProfiles] = useState<AmazonAdsProfile[]>([]);
  const [selectedProfileId, setSelectedProfileId] = useState('');
  const [callbackRegion, setCallbackRegion] = useState('NA');
  const [callbackStoreId, setCallbackStoreId] = useState('');

  const reset = useCallback(() => {
    setStep('store');
    setRegion('NA');
    setStoreId(defaultStoreId || '');
    setAuthorizeUrl('');
    setError(null);
    setCopied(false);
    setCallbackUrl('');
    setProfiles([]);
    setSelectedProfileId('');
  }, [defaultStoreId]);

  // Process an OAuth callback (code + state) into the selectable profile list.
  const runCallback = useCallback(async (code: string, state: string) => {
    setLoading(true);
    setError(null);
    try {
      const result = await amazonAdsCallback(code, state);
      setProfiles(result.profiles || []);
      setCallbackRegion(result.region?.toUpperCase() || 'NA');
      setCallbackStoreId(result.storeId || '');
      setStep('select');
    } catch (err: any) {
      setError(err.message || '授权回调处理失败');
      setStep('authorize');
    } finally {
      setLoading(false);
    }
  }, []);

  // Resume from a redirect (?code&state) when the wizard is opened for it.
  useEffect(() => {
    if (open && resumeCode && resumeState) {
      void runCallback(resumeCode, resumeState);
    }
  }, [open, resumeCode, resumeState, runCallback]);

  useEffect(() => {
    if (!open) reset();
  }, [open, reset]);

  if (!open) return null;

  async function handleGetAuthorizeUrl() {
    if (!storeId) {
      setError('请选择要绑定的 Amazon 店铺');
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const result = await getAmazonAdsAuthorizeUrl(region, storeId);
      setAuthorizeUrl(result.authorizeUrl);
      setStep('authorize');
    } catch (err: any) {
      setError(err.message || '获取授权链接失败');
    } finally {
      setLoading(false);
    }
  }

  function handleAuthorize() {
    if (authorizeUrl) window.open(authorizeUrl, '_blank', 'noopener');
  }

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(authorizeUrl);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      setError('复制失败，请手动复制链接');
    }
  }

  function parseAndRunCallback() {
    setError(null);
    let code = '';
    let state = '';
    const raw = callbackUrl.trim();
    try {
      // Accept a full redirected URL or a bare "code=...&state=..." query string.
      const queryPart = raw.includes('?') ? raw.substring(raw.indexOf('?') + 1) : raw;
      const params = new URLSearchParams(queryPart);
      code = params.get('code') || '';
      state = params.get('state') || '';
    } catch {
      /* fall through to validation below */
    }
    if (!code || !state) {
      setError('无法从链接中解析 code 与 state，请粘贴完整的回调地址');
      return;
    }
    void runCallback(code, state);
  }

  async function handleBind() {
    const targetStore = callbackStoreId || storeId;
    const chosen = profiles.find((p) => p.profileId === selectedProfileId);
    if (!chosen) {
      setError('请选择一个广告账户');
      return;
    }
    setLoading(true);
    setError(null);
    try {
      await bindAmazonAdsProfile({
        storeId: targetStore,
        profileId: chosen.profileId,
        region: callbackRegion,
        marketplaceId: chosen.marketplaceId,
        sellerId: chosen.sellerStringId,
        accountName: chosen.accountName,
      });
      setStep('done');
      onConnected();
    } catch (err: any) {
      setError(err.message || '绑定广告账户失败');
    } finally {
      setLoading(false);
    }
  }

  const storeName = (id?: string) => stores.find((s) => s.id === id)?.name || '未选择店铺';

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o && !loading) onClose(); }}>
      <DialogContent className="block gap-0 p-6 max-h-[90vh] overflow-y-auto w-full sm:max-w-lg rounded-xl border-0 bg-white shadow-xl">
        <div className="flex items-center justify-between mb-4">
          <DialogTitle className="text-base font-semibold text-slate-900">连接亚马逊店铺（广告授权）</DialogTitle>
        </div>

        {/* Step indicator */}
        <div className="flex items-center gap-2 mb-5 text-xs">
          {[
            { k: 'store', n: '1 店铺信息' },
            { k: 'authorize', n: '2 广告授权' },
            { k: 'select', n: '3 选择账户' },
          ].map((s, i) => (
            <div key={s.k} className="flex items-center gap-2">
              <span className={cn(
                'px-2.5 py-1 rounded-full font-medium',
                step === s.k ? 'bg-indigo-600 text-white'
                  : (['store', 'authorize', 'select', 'done'].indexOf(step) > i ? 'bg-emerald-100 text-emerald-700' : 'bg-slate-100 text-slate-500'),
              )}>{s.n}</span>
              {i < 2 && <ArrowRight size={12} className="text-slate-300" />}
            </div>
          ))}
        </div>

        {error && (
          <div className="bg-red-50 border border-red-200 rounded-lg p-2.5 mb-4 flex items-start gap-2 text-sm text-red-700">
            <AlertTriangle size={14} className="mt-0.5 flex-shrink-0" /> <span>{error}</span>
          </div>
        )}

        {/* Step 1 — store info / region */}
        {step === 'store' && (
          <div className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">选择亚马逊区域 <span className="text-red-500">*</span></label>
              <div className="space-y-2">
                {REGIONS.map((r) => (
                  <button
                    key={r.key}
                    onClick={() => setRegion(r.key)}
                    className={cn(
                      'w-full text-left p-3 rounded-lg border transition-colors',
                      region === r.key ? 'border-indigo-400 bg-indigo-50' : 'border-slate-200 hover:bg-slate-50',
                    )}
                  >
                    <div className="flex items-center justify-between">
                      <span className="text-sm font-medium text-slate-900">{r.label}</span>
                      {region === r.key && <Check size={16} className="text-indigo-600" />}
                    </div>
                    <p className="text-xs text-slate-500 mt-0.5">{r.countries}</p>
                  </button>
                ))}
              </div>
            </div>
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">绑定店铺 <span className="text-red-500">*</span></label>
              <select
                value={storeId}
                onChange={(e) => setStoreId(e.target.value)}
                className="w-full h-10 rounded-lg border border-slate-200 px-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
              >
                <option value="">请选择 Amazon 店铺</option>
                {stores.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </div>

            <div className="flex justify-end pt-2">
              <button
                onClick={handleGetAuthorizeUrl}
                disabled={loading || !storeId}
                className="inline-flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 disabled:opacity-60"
              >
                {loading ? <Loader2 size={15} className="animate-spin" /> : <ArrowRight size={15} />} 下一步：去授权
              </button>
            </div>
          </div>
        )}

        {/* Step 2 — authorize */}
        {step === 'authorize' && (
          <div className="space-y-4">
            <p className="text-sm text-slate-600">
              点击「去授权」将打开亚马逊登录页面。登录后选择要授权的广告账户并点击「允许 / Continue」，
              亚马逊会带着授权码跳转回应用。若新标签页未自动返回，请把浏览器地址栏的完整回调链接粘贴到下方。
            </p>
            <div className="flex flex-wrap items-center gap-2">
              <button onClick={handleAuthorize} className="inline-flex items-center gap-2 px-4 py-2 bg-slate-900 text-white text-sm font-medium rounded-lg hover:bg-slate-800">
                <ExternalLink size={15} /> 去授权
              </button>
              <button onClick={handleCopy} className="inline-flex items-center gap-2 px-4 py-2 bg-slate-100 text-slate-700 text-sm font-medium rounded-lg hover:bg-slate-200">
                {copied ? <Check size={15} className="text-emerald-600" /> : <Copy size={15} />} {copied ? '已复制' : '复制授权链接'}
              </button>
            </div>
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">粘贴授权后的回调链接</label>
              <textarea
                value={callbackUrl}
                onChange={(e) => setCallbackUrl(e.target.value)}
                rows={3}
                placeholder="https://your-app/amazon-ads/callback?code=...&state=..."
                className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
              />
            </div>
            <div className="flex items-center justify-between pt-2">
              <button onClick={() => setStep('store')} className="inline-flex items-center gap-1.5 text-sm text-slate-600 hover:text-slate-800">
                <ArrowLeft size={14} /> 上一步
              </button>
              <button
                onClick={parseAndRunCallback}
                disabled={loading || !callbackUrl.trim()}
                className="inline-flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 disabled:opacity-60"
              >
                {loading ? <Loader2 size={15} className="animate-spin" /> : <ArrowRight size={15} />} 读取广告账户
              </button>
            </div>
          </div>
        )}

        {/* Step 3 — select profiles */}
        {step === 'select' && (
          <div className="space-y-4">
            <p className="text-sm text-slate-600">
              选择要绑定到「{storeName(callbackStoreId || storeId)}」的广告账户（Profile）。
            </p>
            {profiles.length === 0 ? (
              <div className="text-sm text-slate-500 bg-slate-50 rounded-lg p-4 text-center">未返回任何广告账户。</div>
            ) : (
              <div className="space-y-2 max-h-72 overflow-y-auto">
                {profiles.map((p) => (
                  <label
                    key={p.profileId}
                    className={cn(
                      'flex items-start gap-3 p-3 rounded-lg border cursor-pointer transition-colors',
                      selectedProfileId === p.profileId ? 'border-indigo-400 bg-indigo-50' : 'border-slate-200 hover:bg-slate-50',
                    )}
                  >
                    <input
                      type="radio"
                      name="amazon-ads-profile"
                      checked={selectedProfileId === p.profileId}
                      onChange={() => setSelectedProfileId(p.profileId)}
                      className="mt-1"
                    />
                    <div className="flex-1 min-w-0">
                      <div className="text-sm font-medium text-slate-900">{p.accountName || p.profileId}</div>
                      <div className="text-xs text-slate-500 mt-0.5">
                        Profile: {p.profileId}
                        {p.countryCode ? ` · ${p.countryCode}` : ''}
                        {p.currencyCode ? ` · ${p.currencyCode}` : ''}
                      </div>
                      {(p.sellerStringId || p.marketplaceId) && (
                        <div className="text-xs text-slate-400 mt-0.5">
                          {p.sellerStringId ? `MerchantID: ${p.sellerStringId}` : ''}
                          {p.marketplaceId ? ` · Marketplace: ${p.marketplaceId}` : ''}
                        </div>
                      )}
                    </div>
                  </label>
                ))}
              </div>
            )}
            <div className="flex items-center justify-between pt-2">
              <button onClick={() => setStep('authorize')} className="inline-flex items-center gap-1.5 text-sm text-slate-600 hover:text-slate-800">
                <ArrowLeft size={14} /> 上一步
              </button>
              <button
                onClick={handleBind}
                disabled={loading || profiles.length === 0}
                className="inline-flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 disabled:opacity-60"
              >
                {loading ? <Loader2 size={15} className="animate-spin" /> : <CheckCircle2 size={15} />} 绑定并完成
              </button>
            </div>
          </div>
        )}

        {/* Done */}
        {step === 'done' && (
          <div className="space-y-4 text-center py-4">
            <div className="w-14 h-14 rounded-2xl bg-emerald-50 flex items-center justify-center text-emerald-500 mx-auto">
              <CheckCircle2 size={30} />
            </div>
            <div>
              <h3 className="text-base font-semibold text-slate-900">连接成功</h3>
              <p className="text-sm text-slate-500 mt-1">已将所选广告账户绑定到店铺，连接状态为「已连接」。</p>
            </div>
            <div className="bg-amber-50 border border-amber-200 rounded-lg p-3 text-left text-xs text-amber-700">
              <strong>商品上传 / SP-API：</strong>
              本次授权仅用于「广告账户」。如需通过 Amazon Selling Partner API（SP-API）上传/同步商品，
              需单独完成 Selling Partner 授权，属于后续步骤，不影响当前广告账户连接。
            </div>
            <div className="flex justify-center">
              <button onClick={onClose} className="px-4 py-2 bg-slate-900 text-white text-sm font-medium rounded-lg hover:bg-slate-800">完成</button>
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
