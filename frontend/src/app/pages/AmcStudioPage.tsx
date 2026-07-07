import { useState, useEffect, useCallback } from 'react';
import { Boxes, Users, Lock, AlertTriangle, Layers, Unlock } from 'lucide-react';
import { fetchAmcModels, fetchAmcAudiences, activateDataSource, type AmcTemplates } from '../lib/api';
import { useStoreId } from '../lib/useStoreId';
import { cn } from '../lib/utils';

type TabKey = 'models' | 'audiences';

const TABS: { key: TabKey; label: string; icon: typeof Layers }[] = [
  { key: 'models', label: 'AMC模型库', icon: Layers },
  { key: 'audiences', label: '用户受众创建', icon: Users },
];

function Loading() {
  return (
    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
      {Array.from({ length: 4 }).map((_, i) => (
        <div key={i} className="h-28 bg-slate-100 rounded-xl animate-pulse" />
      ))}
    </div>
  );
}

export function AmcStudioPage() {
  const { storeId, loading: storeLoading, error: storeError } = useStoreId();
  const [tab, setTab] = useState<TabKey>('models');
  const [models, setModels] = useState<AmcTemplates | null>(null);
  const [audiences, setAudiences] = useState<AmcTemplates | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activating, setActivating] = useState(false);

  const loadTab = useCallback(
    async (which: TabKey) => {
      if (!storeId) return;
      setLoading(true);
      setError(null);
      try {
        if (which === 'models') {
          if (!models) setModels(await fetchAmcModels(storeId));
        } else if (!audiences) {
          setAudiences(await fetchAmcAudiences(storeId));
        }
      } catch (err: any) {
        setError(err?.message || '加载 AMC 数据失败');
      } finally {
        setLoading(false);
      }
    },
    [storeId, models, audiences],
  );

  useEffect(() => {
    setModels(null);
    setAudiences(null);
  }, [storeId]);

  useEffect(() => {
    loadTab(tab);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, storeId]);

  function retry() {
    if (tab === 'models') setModels(null);
    else setAudiences(null);
    loadTab(tab);
  }

  /** Activate the AMC data source (item 8), then refresh both AMC surfaces. */
  async function handleActivate() {
    if (!storeId) return;
    setActivating(true);
    setError(null);
    try {
      await activateDataSource(storeId, 'amc');
      // Both tabs gate on the same AMC source — clear both caches and reload.
      setModels(null);
      setAudiences(null);
      await loadTab(tab);
    } catch (err: any) {
      setError(err?.message || '激活 AMC 数据源失败');
    } finally {
      setActivating(false);
    }
  }

  const current = tab === 'models' ? models : audiences;

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <Boxes size={24} className="text-blue-600" />
        <div>
          <h1 className="text-2xl font-bold text-slate-900">AMC 数据工作室</h1>
          <p className="text-sm text-slate-500 mt-0.5">亚马逊营销云（AMC）模型库与用户受众创建模板</p>
        </div>
      </div>

      <div className="flex gap-1 border-b border-slate-200">
        {TABS.map((t) => {
          const Icon = t.icon;
          const active = tab === t.key;
          return (
            <button
              key={t.key}
              onClick={() => setTab(t.key)}
              className={cn(
                'inline-flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium border-b-2 -mb-px transition-colors',
                active ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500 hover:text-slate-700',
              )}
            >
              <Icon size={15} />
              {t.label}
            </button>
          );
        })}
      </div>

      {storeLoading || loading ? (
        <Loading />
      ) : storeError || error ? (
        <div className="flex flex-col items-center justify-center py-16 text-center">
          <AlertTriangle size={44} className="text-red-300 mb-3" />
          <p className="text-base font-medium text-slate-700">加载失败</p>
          <p className="text-sm text-slate-500 mt-1 max-w-md">{storeError || error}</p>
          <button onClick={retry} className="mt-4 px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700">
            重试
          </button>
        </div>
      ) : !storeId ? (
        <div className="flex flex-col items-center justify-center py-16 text-center">
          <Boxes size={44} className="text-slate-300 mb-3" />
          <p className="text-base font-medium text-slate-500">请选择店铺</p>
        </div>
      ) : (
        <>
          {/* Activation gating banner (Req 30.7) */}
          {current && !current.activated && (
            <div className="flex items-start gap-3 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3">
              <Lock size={18} className="text-amber-500 flex-shrink-0 mt-0.5" />
              <div className="flex-1">
                <p className="text-sm font-medium text-amber-800">该功能需要激活</p>
                <p className="text-sm text-amber-700 mt-0.5">
                  {current.message || 'AMC 功能需先激活亚马逊营销云实例后方可运行。'}
                </p>
              </div>
              <button
                onClick={handleActivate}
                disabled={activating}
                className="flex-shrink-0 inline-flex items-center gap-2 px-3 py-1.5 rounded-lg bg-amber-600 text-white text-sm font-medium hover:bg-amber-700 disabled:opacity-60"
              >
                <Unlock size={15} />
                {activating ? '激活中...' : '激活'}
              </button>
            </div>
          )}

          {/* Activated confirmation */}
          {current && current.activated && (
            <div className="flex items-start gap-3 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3">
              <Unlock size={18} className="text-emerald-500 flex-shrink-0 mt-0.5" />
              <div>
                <p className="text-sm font-medium text-emerald-800">AMC 数据源已激活</p>
                <p className="text-sm text-emerald-700 mt-0.5">
                  {current.message || 'AMC 已激活，可基于已接入的数据运行模型与创建受众。'}
                </p>
              </div>
            </div>
          )}

          {!current || current.templates.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-16 text-center">
              <Layers size={44} className="text-slate-300 mb-3" />
              <p className="text-base font-medium text-slate-500">暂无可用模板</p>
            </div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {current.templates.map((tpl) => (
                <div key={tpl.key} className="bg-white rounded-xl border border-slate-200 p-4">
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <p className="text-sm font-semibold text-slate-900">{tpl.name}</p>
                      {tpl.category && <p className="text-xs text-slate-400 mt-0.5">{tpl.category}</p>}
                    </div>
                    {tpl.activationRequired && (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium border bg-slate-100 text-slate-500 border-slate-200">
                        <Lock size={11} /> 需激活
                      </span>
                    )}
                  </div>
                  {tpl.description && <p className="text-sm text-slate-600 mt-2">{tpl.description}</p>}
                  <button
                    disabled={tpl.activationRequired}
                    className={cn(
                      'mt-3 px-3 py-1.5 rounded-lg text-sm font-medium',
                      tpl.activationRequired
                        ? 'bg-slate-100 text-slate-400 cursor-not-allowed'
                        : 'bg-blue-600 text-white hover:bg-blue-700',
                    )}
                  >
                    {tab === 'models' ? '运行模型' : '创建受众'}
                  </button>
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}
