import { useState, type FormEvent, type KeyboardEvent } from 'react';
import { useNavigate } from 'react-router';
import {
  Zap,
  Target,
  LayoutDashboard,
  Bot,
  ShieldCheck,
  Mail,
  Lock,
  Eye,
  EyeOff,
  Loader2,
} from 'lucide-react';
import { setAccessToken, setCurrentUser, apiUrl } from '../lib/auth';

export function LoginPage() {
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [remember, setRemember] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const features = [
    { icon: Target, label: '目标驱动优化' },
    { icon: LayoutDashboard, label: 'ERP 一体化' },
    { icon: Bot, label: 'AI 审批执行' },
    { icon: ShieldCheck, label: '多角色权限' },
  ];

  async function handleSubmit(e?: FormEvent) {
    if (e) e.preventDefault();
    setError('');

    if (!email.trim()) {
      setError('请输入邮箱或用户名');
      return;
    }
    if (!password) {
      setError('请输入密码');
      return;
    }

    setLoading(true);
    try {
      const res = await fetch(apiUrl('/api/auth/login'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password, remember }),
      });

      const json = await res.json();

      if (!res.ok) {
        const code = json.error?.code || '';
        if (code === 'USER_DISABLED' || code === 'ACCOUNT_DISABLED') {
          setError('账号已被禁用');
        } else {
          setError('账号或密码错误');
        }
        return;
      }

      const data = json.data || json;
      if (data.token) {
        setAccessToken(data.token);
      }
      if (data.user) {
        setCurrentUser(data.user);
      }

      navigate('/', { replace: true });
    } catch {
      setError('网络错误，请稍后重试');
    } finally {
      setLoading(false);
    }
  }

  function handleKeyDown(e: KeyboardEvent) {
    if (e.key === 'Enter') {
      handleSubmit();
    }
  }

  return (
    <div className="flex min-h-screen bg-[#F8FAFC]">
      {/* Left Side — Branding */}
      <div className="hidden lg:flex lg:w-1/2 xl:w-[45%] bg-white border-r border-slate-200 flex-col justify-center px-16 xl:px-24">
        <div className="flex items-center gap-3 mb-8">
          <div className="w-10 h-10 bg-gradient-to-br from-blue-600 to-indigo-600 rounded-lg flex items-center justify-center shadow-sm">
            <Zap size={20} className="text-white" />
          </div>
          <div>
            <h1 className="text-2xl font-bold text-slate-900 leading-tight">AdPilot AI</h1>
            <p className="text-sm text-slate-500 leading-tight">跨境电商智能经营平台</p>
          </div>
        </div>

        <p className="text-base text-slate-600 mb-10 leading-relaxed">
          广告自动化 · ERP · AI 审批，一站式经营
        </p>

        <div className="space-y-5">
          {features.map(({ icon: Icon, label }) => (
            <div key={label} className="flex items-center gap-4">
              <div className="w-10 h-10 rounded-lg bg-blue-50 flex items-center justify-center flex-shrink-0">
                <Icon size={20} className="text-blue-600" />
              </div>
              <span className="text-sm font-medium text-slate-700">{label}</span>
            </div>
          ))}
        </div>
      </div>

      {/* Right Side — Login Form */}
      <div className="flex-1 flex items-center justify-center px-6 py-12">
        <div className="w-full max-w-sm">
          {/* Mobile Logo */}
          <div className="flex items-center gap-3 mb-8 lg:hidden">
            <div className="w-9 h-9 bg-gradient-to-br from-blue-600 to-indigo-600 rounded-lg flex items-center justify-center shadow-sm">
              <Zap size={18} className="text-white" />
            </div>
            <div>
              <p className="text-lg font-bold text-slate-900 leading-tight">AdPilot AI</p>
              <p className="text-xs text-slate-500 leading-tight">跨境电商智能经营平台</p>
            </div>
          </div>

          <div className="mb-8">
            <h2 className="text-xl font-semibold text-slate-800">登录</h2>
            <p className="text-sm text-slate-500 mt-1">请使用公司账号登录</p>
          </div>

          <form onSubmit={handleSubmit} className="space-y-5">
            {/* Email */}
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">
                邮箱 / 用户名
              </label>
              <div className="relative">
                <Mail
                  size={16}
                  className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 pointer-events-none"
                />
                <input
                  type="text"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  onKeyDown={handleKeyDown}
                  placeholder="请输入邮箱或用户名"
                  className="w-full pl-10 pr-4 py-2.5 text-sm border border-slate-300 rounded-lg bg-white text-slate-800 placeholder-slate-400 outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-500 transition-colors"
                />
              </div>
            </div>

            {/* Password */}
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">密码</label>
              <div className="relative">
                <Lock
                  size={16}
                  className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 pointer-events-none"
                />
                <input
                  type={showPassword ? 'text' : 'password'}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  onKeyDown={handleKeyDown}
                  placeholder="请输入密码"
                  className="w-full pl-10 pr-10 py-2.5 text-sm border border-slate-300 rounded-lg bg-white text-slate-800 placeholder-slate-400 outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-500 transition-colors"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600 transition-colors"
                >
                  {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
                </button>
              </div>
            </div>

            {/* Remember login */}
            <div className="flex items-center justify-between">
              <label className="flex items-center gap-2 cursor-pointer">
                <input
                  type="checkbox"
                  checked={remember}
                  onChange={(e) => setRemember(e.target.checked)}
                  className="w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                />
                <span className="text-sm text-slate-600">记住登录</span>
              </label>
            </div>

            {/* Error */}
            {error && (
              <div className="px-3 py-2.5 bg-red-50 border border-red-200 rounded-lg">
                <p className="text-sm text-red-600">{error}</p>
              </div>
            )}

            {/* Submit */}
            <button
              type="submit"
              disabled={loading}
              className="w-full flex items-center justify-center gap-2 py-2.5 bg-blue-600 text-white text-sm font-medium rounded-lg hover:bg-blue-700 disabled:opacity-60 disabled:cursor-not-allowed transition-colors shadow-sm"
            >
              {loading && <Loader2 size={16} className="animate-spin" />}
              {loading ? '登录中...' : '登录'}
            </button>
          </form>
        </div>
      </div>
    </div>
  );
}
