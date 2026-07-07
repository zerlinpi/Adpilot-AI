import { useState, useEffect } from 'react';
import { Save, Plug, CheckCircle, XCircle, Loader2, Eye, EyeOff } from 'lucide-react';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import {
  fetchAiSettings,
  updateAiSettings,
  testAiConnection,
  type AiSettings,
} from '../lib/api';

// Common OpenAI-compatible providers. Selecting one fills base URL + a sensible
// default model; the API is the same OpenAI /chat/completions protocol.
const PROVIDER_PRESETS: Array<{
  id: string;
  label: string;
  baseUrl: string;
  model: string;
  hint?: string;
}> = [
    { id: 'openai', label: 'OpenAI', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini' },
    { id: 'deepseek', label: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
    { id: 'moonshot', label: 'Moonshot (Kimi)', baseUrl: 'https://api.moonshot.cn/v1', model: 'moonshot-v1-8k' },
    { id: 'qwen', label: '通义千问 (DashScope)', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
    { id: 'azure', label: 'Azure OpenAI', baseUrl: 'https://YOUR-RESOURCE.openai.azure.com/openai/deployments/YOUR-DEPLOYMENT', model: 'gpt-4o-mini', hint: '需在 URL 中填入你的资源名与部署名' },
    { id: 'ollama', label: '本地 Ollama', baseUrl: 'http://localhost:11434/v1', model: 'llama3.1' },
    { id: 'custom', label: '自定义 (Custom)', baseUrl: '', model: '' },
  ];

// A sensible default so the form is always editable, even before the initial
// fetch resolves or when it fails (e.g. backend down / 502). Without this the
// inputs become frozen controlled components and can't be typed into.
const DEFAULT_AI_SETTINGS: AiSettings = {
  provider: 'openai',
  baseUrl: '',
  apiKeyConfigured: false,
  model: '',
  temperature: 0.7,
  maxTokens: 1024,
  enabled: false,
};

export function AISettingsPage() {
  const [settings, setSettings] = useState<AiSettings>(DEFAULT_AI_SETTINGS);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [testResult, setTestResult] = useState<string | null>(null);
  const [apiKey, setApiKey] = useState('');
  const [showKey, setShowKey] = useState(false);

  useEffect(() => {
    load();
  }, []);

  async function load() {
    try {
      setLoading(true);
      setError(null);
      const data = await fetchAiSettings();
      setSettings(data);
    } catch (e: any) {
      // Keep the form usable even when the saved config can't be loaded.
      setSettings(DEFAULT_AI_SETTINGS);
      setError(e.message || '加载 AI 配置失败');
    } finally {
      setLoading(false);
    }
  }

  function patch(p: Partial<AiSettings>) {
    setSettings((prev) => ({ ...(prev ?? DEFAULT_AI_SETTINGS), ...p }));
    setSaved(false);
  }

  function applyPreset(id: string) {
    const preset = PROVIDER_PRESETS.find((p) => p.id === id);
    if (!preset) return;
    patch({
      provider: preset.id,
      baseUrl: preset.id === 'custom' ? settings?.baseUrl ?? '' : preset.baseUrl,
      model: preset.id === 'custom' ? settings?.model ?? '' : preset.model,
    });
  }

  async function handleSave() {
    if (!settings) return;
    try {
      setSaving(true);
      setError(null);
      const payload: Partial<AiSettings> & { apiKey?: string } = {
        provider: settings.provider,
        baseUrl: settings.baseUrl,
        model: settings.model,
        temperature: settings.temperature,
        maxTokens: settings.maxTokens,
        enabled: settings.enabled,
      };
      // Only send the key if the user typed a new one (blank = keep existing).
      if (apiKey.trim()) payload.apiKey = apiKey.trim();
      const updated = await updateAiSettings(payload);
      setSettings(updated);
      setApiKey('');
      setSaved(true);
    } catch (e: any) {
      setError(e.message || '保存失败');
    } finally {
      setSaving(false);
    }
  }

  async function handleTest() {
    try {
      setTesting(true);
      setTestResult(null);
      setError(null);
      const res = await testAiConnection();
      setTestResult(res.result);
    } catch (e: any) {
      setTestResult('FAILED - ' + (e.message || '连接测试失败'));
    } finally {
      setTesting(false);
    }
  }

  if (loading) {
    return (
      <div className="p-6">
        <div className="flex items-center gap-2 text-slate-500">
          <Loader2 className="animate-spin" size={18} /> 加载中...
        </div>
      </div>
    );
  }

  return (
    <div className="p-6 max-w-3xl">
      <ErpPageHeader
        title="AI 接口配置"
        description="对接任意 OpenAI 兼容的大模型服务（OpenAI / DeepSeek / Kimi / 通义千问 / Azure / 本地 Ollama 等）。未启用时系统自动使用内置模板。"
      />

      {error && (
        <div className="mb-4 px-4 py-3 rounded-lg bg-red-50 border border-red-200 text-sm text-red-700">
          {error}
        </div>
      )}

      <div className="bg-white border border-slate-200 rounded-xl p-6 space-y-5">
        {/* Provider preset */}
        <div>
          <label className="block text-sm font-medium text-slate-700 mb-1.5">服务商预设</label>
          <div className="flex flex-wrap gap-2">
            {PROVIDER_PRESETS.map((p) => (
              <button
                key={p.id}
                onClick={() => applyPreset(p.id)}
                className={`px-3 py-1.5 rounded-lg text-sm border transition-colors ${settings?.provider === p.id
                  ? 'bg-blue-50 border-blue-300 text-blue-700'
                  : 'bg-white border-slate-200 text-slate-600 hover:bg-slate-50'
                  }`}
              >
                {p.label}
              </button>
            ))}
          </div>
        </div>

        {/* Base URL */}
        <div>
          <label className="block text-sm font-medium text-slate-700 mb-1.5">API Base URL</label>
          <input
            type="text"
            value={settings?.baseUrl ?? ''}
            onChange={(e) => patch({ baseUrl: e.target.value })}
            placeholder="https://api.openai.com/v1"
            className="w-full px-3 py-2 rounded-lg border border-slate-200 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200"
          />
          <p className="mt-1 text-xs text-slate-400">兼容 OpenAI 协议，自动追加 /chat/completions。</p>
        </div>

        {/* API Key */}
        <div>
          <label className="block text-sm font-medium text-slate-700 mb-1.5">API Key</label>
          <div className="relative">
            <input
              type={showKey ? 'text' : 'password'}
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
              placeholder={settings?.apiKeyConfigured ? `已配置（${settings?.apiKeyMasked ?? '****'}），留空则不修改` : '请输入 API Key'}
              className="w-full px-3 py-2 pr-10 rounded-lg border border-slate-200 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200"
            />
            <button
              type="button"
              onClick={() => setShowKey((s) => !s)}
              className="absolute right-2 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
            >
              {showKey ? <EyeOff size={16} /> : <Eye size={16} />}
            </button>
          </div>
        </div>

        {/* Model */}
        <div>
          <label className="block text-sm font-medium text-slate-700 mb-1.5">模型 (Model)</label>
          <input
            type="text"
            value={settings?.model ?? ''}
            onChange={(e) => patch({ model: e.target.value })}
            placeholder="gpt-4o-mini"
            className="w-full px-3 py-2 rounded-lg border border-slate-200 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200"
          />
        </div>

        {/* Temperature + Max tokens */}
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1.5">Temperature</label>
            <input
              type="number"
              step="0.1"
              min="0"
              max="2"
              value={settings?.temperature ?? 0.7}
              onChange={(e) => patch({ temperature: parseFloat(e.target.value) })}
              className="w-full px-3 py-2 rounded-lg border border-slate-200 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1.5">Max Tokens</label>
            <input
              type="number"
              min="1"
              value={settings?.maxTokens ?? 1024}
              onChange={(e) => patch({ maxTokens: parseInt(e.target.value, 10) })}
              className="w-full px-3 py-2 rounded-lg border border-slate-200 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200"
            />
          </div>
        </div>

        {/* Enabled toggle */}
        <div className="flex items-center justify-between py-2 border-t border-slate-100">
          <div>
            <p className="text-sm font-medium text-slate-700">启用 AI</p>
            <p className="text-xs text-slate-400">关闭时所有 AI 功能回退到内置模板。</p>
          </div>
          <button
            onClick={() => patch({ enabled: !settings?.enabled })}
            className={`relative w-11 h-6 rounded-full transition-colors ${settings?.enabled ? 'bg-blue-500' : 'bg-slate-300'
              }`}
          >
            <span
              className={`absolute top-0.5 left-0.5 w-5 h-5 bg-white rounded-full transition-transform ${settings?.enabled ? 'translate-x-5' : ''
                }`}
            />
          </button>
        </div>

        {/* Actions */}
        <div className="flex items-center gap-3 pt-2">
          <button
            onClick={handleSave}
            disabled={saving}
            className="flex items-center gap-2 px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:opacity-60"
          >
            {saving ? <Loader2 className="animate-spin" size={16} /> : <Save size={16} />}
            保存配置
          </button>
          <button
            onClick={handleTest}
            disabled={testing}
            className="flex items-center gap-2 px-4 py-2 rounded-lg border border-slate-200 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-60"
          >
            {testing ? <Loader2 className="animate-spin" size={16} /> : <Plug size={16} />}
            测试连接
          </button>
          {saved && (
            <span className="flex items-center gap-1 text-sm text-green-600">
              <CheckCircle size={16} /> 已保存
            </span>
          )}
        </div>

        {testResult && (
          <div
            className={`flex items-start gap-2 px-4 py-3 rounded-lg text-sm border ${testResult.startsWith('OK')
              ? 'bg-green-50 border-green-200 text-green-700'
              : 'bg-amber-50 border-amber-200 text-amber-700'
              }`}
          >
            {testResult.startsWith('OK') ? <CheckCircle size={16} /> : <XCircle size={16} />}
            <span className="break-all">{testResult}</span>
          </div>
        )}
      </div>
    </div>
  );
}
