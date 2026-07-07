import { useState } from 'react';
import { authFetch } from '../lib/auth';
import {
  User, Mail, Phone, Building2, Shield, Key, CheckCircle, XCircle,
  Monitor, Clock, Eye, EyeOff, Lock,
} from 'lucide-react';
import { useApiQuery } from '../lib/hooks/useApiQuery';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { ErpErrorState } from '../components/erp/ErpErrorState';
import { ErpLoadingSkeleton } from '../components/erp/ErpLoadingSkeleton';
import { Card, CardHeader, CardTitle, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Label } from '../components/ui/label';
import { Input } from '../components/ui/input';
import { Separator } from '../components/ui/separator';

interface ProfileData {
  name: string;
  email: string;
  phone: string;
  department: string;
  role: string;
  roles: Array<{ id: string; name: string; description: string }>;
  permissions: string[];
  recentLogins: Array<{ id: string; time: string; ip: string; browser: string; status: string }>;
}

function InfoRow({ icon: Icon, label, value }: { icon: typeof User; label: string; value: string }) {
  return (
    <div className="flex items-center gap-3 py-3">
      <div className="w-8 h-8 rounded-lg bg-slate-100 flex items-center justify-center text-slate-500">
        <Icon size={16} />
      </div>
      <div className="flex-1 min-w-0">
        <p className="text-xs text-slate-500">{label}</p>
        <p className="text-sm text-slate-900 font-medium truncate">{value}</p>
      </div>
    </div>
  );
}

export function ProfilePage() {
  const profileQuery = useApiQuery<ProfileData | null>(
    ['profile'],
    async () => {
      const res = await authFetch('/api/profile');
      const json = await res.json();
      if (!json.success) throw new Error(json.error?.message || '加载失败');
      return json.data || null;
    },
  );
  const data = profileQuery.data ?? null;
  const loading = profileQuery.isLoading;
  const error = profileQuery.isError ? profileQuery.error?.message ?? '加载失败' : null;
  const fetchData = () => profileQuery.refetch();

  // Password form
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showCurrentPw, setShowCurrentPw] = useState(false);
  const [showNewPw, setShowNewPw] = useState(false);
  const [showConfirmPw, setShowConfirmPw] = useState(false);
  const [passwordError, setPasswordError] = useState('');
  const [passwordSuccess, setPasswordSuccess] = useState('');

  const getPasswordStrength = (pw: string): { level: number; label: string; color: string } => {
    if (pw.length === 0) return { level: 0, label: '', color: 'bg-slate-200' };
    let score = 0;
    if (pw.length >= 8) score++;
    if (pw.length >= 12) score++;
    if (/[a-z]/.test(pw)) score++;
    if (/[A-Z]/.test(pw)) score++;
    if (/[0-9]/.test(pw)) score++;
    if (/[^a-zA-Z0-9]/.test(pw)) score++;

    if (score <= 2) return { level: 1, label: '弱', color: 'bg-red-500' };
    if (score <= 4) return { level: 2, label: '中', color: 'bg-amber-500' };
    return { level: 3, label: '强', color: 'bg-emerald-500' };
  };

  const handleChangePassword = () => {
    setPasswordError('');
    setPasswordSuccess('');

    if (!currentPassword || !newPassword || !confirmPassword) {
      setPasswordError('请填写所有密码字段');
      return;
    }
    if (newPassword.length < 8) {
      setPasswordError('新密码至少 8 位');
      return;
    }
    if (!/[a-z]/.test(newPassword) || !/[A-Z]/.test(newPassword) || !/[0-9]/.test(newPassword)) {
      setPasswordError('新密码需包含大小写字母和数字');
      return;
    }
    if (newPassword === currentPassword) {
      setPasswordError('新密码不能与当前密码相同');
      return;
    }
    if (newPassword !== confirmPassword) {
      setPasswordError('两次输入的新密码不一致');
      return;
    }

    // Simulate success
    setPasswordSuccess('密码修改成功');
    setCurrentPassword('');
    setNewPassword('');
    setConfirmPassword('');
  };

  const strength = getPasswordStrength(newPassword);

  if (loading) return <ErpLoadingSkeleton />;
  if (error) return <ErpErrorState message={error} onRetry={fetchData} />;
  if (!data) return null;

  return (
    <div className="space-y-4">
      <ErpPageHeader title="个人中心" description="查看和管理您的账户信息" />

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* Basic Info Card */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2">
              <User size={18} className="text-indigo-600" />
              基本信息
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="flex items-center gap-4 mb-4">
              <div className="w-16 h-16 rounded-full bg-indigo-100 text-indigo-700 flex items-center justify-center text-2xl font-semibold">
                {(data.name || '?').charAt(0)}
              </div>
              <div>
                <h3 className="text-lg font-semibold text-slate-900">{data.name}</h3>
                <p className="text-sm text-slate-500">{data.role}</p>
              </div>
            </div>
            <Separator className="mb-2" />
            <div className="divide-y divide-slate-100">
              <InfoRow icon={Mail} label="邮箱" value={data.email} />
              <InfoRow icon={Phone} label="手机" value={data.phone} />
              <InfoRow icon={Building2} label="部门" value={data.department} />
              <InfoRow icon={Shield} label="角色" value={data.role} />
            </div>
          </CardContent>
        </Card>

        {/* Change Password Card */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2">
              <Lock size={18} className="text-indigo-600" />
              修改密码
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="currentPw">当前密码</Label>
                <div className="relative">
                  <Input
                    id="currentPw"
                    type={showCurrentPw ? 'text' : 'password'}
                    value={currentPassword}
                    onChange={(e) => setCurrentPassword(e.target.value)}
                    placeholder="请输入当前密码"
                  />
                  <button
                    type="button"
                    onClick={() => setShowCurrentPw(!showCurrentPw)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
                  >
                    {showCurrentPw ? <EyeOff size={16} /> : <Eye size={16} />}
                  </button>
                </div>
              </div>

              <div className="space-y-2">
                <Label htmlFor="newPw">新密码</Label>
                <div className="relative">
                  <Input
                    id="newPw"
                    type={showNewPw ? 'text' : 'password'}
                    value={newPassword}
                    onChange={(e) => setNewPassword(e.target.value)}
                    placeholder="至少 8 位，包含大小写字母和数字"
                  />
                  <button
                    type="button"
                    onClick={() => setShowNewPw(!showNewPw)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
                  >
                    {showNewPw ? <EyeOff size={16} /> : <Eye size={16} />}
                  </button>
                </div>
                {/* Strength indicator */}
                {newPassword.length > 0 && (
                  <div className="space-y-1">
                    <div className="flex gap-1">
                      {[1, 2, 3].map((i) => (
                        <div
                          key={i}
                          className={`h-1.5 flex-1 rounded-full transition-colors ${i <= strength.level ? strength.color : 'bg-slate-200'
                            }`}
                        />
                      ))}
                    </div>
                    <p className={`text-xs ${strength.level === 1 ? 'text-red-500' :
                      strength.level === 2 ? 'text-amber-500' : 'text-emerald-500'
                      }`}>
                      密码强度：{strength.label}
                    </p>
                  </div>
                )}
              </div>

              <div className="space-y-2">
                <Label htmlFor="confirmPw">确认新密码</Label>
                <div className="relative">
                  <Input
                    id="confirmPw"
                    type={showConfirmPw ? 'text' : 'password'}
                    value={confirmPassword}
                    onChange={(e) => setConfirmPassword(e.target.value)}
                    placeholder="请再次输入新密码"
                  />
                  <button
                    type="button"
                    onClick={() => setShowConfirmPw(!showConfirmPw)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
                  >
                    {showConfirmPw ? <EyeOff size={16} /> : <Eye size={16} />}
                  </button>
                </div>
              </div>

              {passwordError && (
                <div className="flex items-center gap-2 text-sm text-red-600 bg-red-50 px-3 py-2 rounded-lg">
                  <XCircle size={14} />
                  {passwordError}
                </div>
              )}
              {passwordSuccess && (
                <div className="flex items-center gap-2 text-sm text-emerald-600 bg-emerald-50 px-3 py-2 rounded-lg">
                  <CheckCircle size={14} />
                  {passwordSuccess}
                </div>
              )}

              <Button className="w-full" onClick={handleChangePassword}>
                修改密码
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* My Roles Card */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2">
              <Shield size={18} className="text-indigo-600" />
              我的角色
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-3">
              {data.roles.map((role) => (
                <div key={role.id} className="flex items-center gap-3 p-3 bg-slate-50 rounded-lg">
                  <div className="w-10 h-10 rounded-lg bg-indigo-50 text-indigo-600 flex items-center justify-center">
                    <Shield size={18} />
                  </div>
                  <div>
                    <p className="text-sm font-medium text-slate-900">{role.name}</p>
                    <p className="text-xs text-slate-500">{role.description}</p>
                  </div>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>

        {/* My Permissions Card */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2">
              <Key size={18} className="text-indigo-600" />
              我的权限
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="flex flex-wrap gap-2">
              {data.permissions.map((perm) => (
                <span
                  key={perm}
                  className="inline-flex items-center px-2.5 py-1 rounded-lg bg-blue-50 text-blue-700 text-xs font-medium"
                >
                  {perm}
                </span>
              ))}
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Recent Login Card */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base flex items-center gap-2">
            <Clock size={18} className="text-indigo-600" />
            最近登录记录
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="bg-white rounded-lg border overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b bg-slate-50">
                  <th className="text-left px-4 py-2.5 font-medium text-slate-600">登录时间</th>
                  <th className="text-center px-4 py-2.5 font-medium text-slate-600">登录状态</th>
                  <th className="text-left px-4 py-2.5 font-medium text-slate-600">IP 地址</th>
                  <th className="text-left px-4 py-2.5 font-medium text-slate-600">浏览器</th>
                </tr>
              </thead>
              <tbody>
                {data.recentLogins.map((log) => (
                  <tr key={log.id} className="border-b hover:bg-slate-50 transition-colors">
                    <td className="px-4 py-2.5 text-slate-900 font-mono text-xs">{log.time}</td>
                    <td className="px-4 py-2.5 text-center">
                      {log.status === 'success' ? (
                        <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded bg-emerald-50 text-emerald-700 text-xs font-medium">
                          <CheckCircle size={12} />
                          成功
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded bg-red-50 text-red-700 text-xs font-medium">
                          <XCircle size={12} />
                          失败
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-2.5 text-slate-600 font-mono text-xs">{log.ip}</td>
                    <td className="px-4 py-2.5">
                      <div className="flex items-center gap-1.5 text-slate-600">
                        <Monitor size={12} className="text-slate-400" />
                        <span className="text-xs">{log.browser}</span>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
