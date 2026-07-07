import { useState } from 'react';
import { authFetch } from '../lib/auth';
import { Lock, Eye, EyeOff, CheckCircle, XCircle, ShieldCheck } from 'lucide-react';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';
import { Card, CardHeader, CardTitle, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Label } from '../components/ui/label';
import { Input } from '../components/ui/input';

interface PasswordRule {
  label: string;
  test: (pw: string) => boolean;
}

const passwordRules: PasswordRule[] = [
  { label: '至少 8 个字符', test: (pw) => pw.length >= 8 },
  { label: '包含小写字母', test: (pw) => /[a-z]/.test(pw) },
  { label: '包含大写字母', test: (pw) => /[A-Z]/.test(pw) },
  { label: '包含数字', test: (pw) => /[0-9]/.test(pw) },
];

function getStrength(pw: string): { level: number; label: string; color: string; textColor: string } {
  if (pw.length === 0) return { level: 0, label: '', color: 'bg-slate-200', textColor: 'text-slate-400' };
  let score = 0;
  if (pw.length >= 8) score++;
  if (pw.length >= 12) score++;
  if (/[a-z]/.test(pw)) score++;
  if (/[A-Z]/.test(pw)) score++;
  if (/[0-9]/.test(pw)) score++;
  if (/[^a-zA-Z0-9]/.test(pw)) score++;

  if (score <= 2) return { level: 1, label: '弱', color: 'bg-red-500', textColor: 'text-red-500' };
  if (score <= 4) return { level: 2, label: '中', color: 'bg-amber-500', textColor: 'text-amber-500' };
  return { level: 3, label: '强', color: 'bg-emerald-500', textColor: 'text-emerald-500' };
}

export function ChangePasswordPage() {
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');

  const [showCurrent, setShowCurrent] = useState(false);
  const [showNew, setShowNew] = useState(false);
  const [showConfirm, setShowConfirm] = useState(false);

  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const strength = getStrength(newPassword);
  const ruleResults = passwordRules.map((r) => ({ ...r, passed: r.test(newPassword) }));
  const allRulesPassed = ruleResults.every((r) => r.passed);

  const handleSubmit = async () => {
    setError('');
    setSuccess('');

    if (!currentPassword) {
      setError('请输入当前密码');
      return;
    }
    if (!newPassword) {
      setError('请输入新密码');
      return;
    }
    if (!allRulesPassed) {
      setError('新密码不满足强度要求');
      return;
    }
    if (newPassword === currentPassword) {
      setError('新密码不能与当前密码相同');
      return;
    }
    if (newPassword !== confirmPassword) {
      setError('两次输入的新密码不一致');
      return;
    }

    setSubmitting(true);
    try {
      const res = await authFetch('/api/auth/change-password', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ currentPassword, newPassword }),
      });
      const json = await res.json();
      if (json.success) {
        setSuccess('密码修改成功，请使用新密码重新登录');
        setCurrentPassword('');
        setNewPassword('');
        setConfirmPassword('');
      } else {
        throw new Error(json.error?.message || '修改失败');
      }
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '修改失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="space-y-4">
      <ErpPageHeader title="修改密码" description="定期修改密码可以提高账户安全性" />

      <div className="max-w-xl">
        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2">
              <Lock size={18} className="text-indigo-600" />
              密码设置
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-5">
              {/* Current Password */}
              <div className="space-y-2">
                <Label htmlFor="cp-current">当前密码</Label>
                <div className="relative">
                  <Input
                    id="cp-current"
                    type={showCurrent ? 'text' : 'password'}
                    value={currentPassword}
                    onChange={(e) => setCurrentPassword(e.target.value)}
                    placeholder="请输入当前密码"
                  />
                  <button
                    type="button"
                    onClick={() => setShowCurrent(!showCurrent)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
                  >
                    {showCurrent ? <EyeOff size={16} /> : <Eye size={16} />}
                  </button>
                </div>
              </div>

              {/* New Password */}
              <div className="space-y-2">
                <Label htmlFor="cp-new">新密码</Label>
                <div className="relative">
                  <Input
                    id="cp-new"
                    type={showNew ? 'text' : 'password'}
                    value={newPassword}
                    onChange={(e) => setNewPassword(e.target.value)}
                    placeholder="请输入新密码"
                  />
                  <button
                    type="button"
                    onClick={() => setShowNew(!showNew)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
                  >
                    {showNew ? <EyeOff size={16} /> : <Eye size={16} />}
                  </button>
                </div>

                {/* Strength Indicator */}
                {newPassword.length > 0 && (
                  <div className="space-y-2 mt-2">
                    <div className="flex gap-1">
                      {[1, 2, 3].map((i) => (
                        <div
                          key={i}
                          className={`h-2 flex-1 rounded-full transition-colors ${i <= strength.level ? strength.color : 'bg-slate-200'
                            }`}
                        />
                      ))}
                    </div>
                    <p className={`text-xs font-medium ${strength.textColor}`}>
                      密码强度：{strength.label}
                    </p>

                    {/* Rules Checklist */}
                    <div className="space-y-1.5 mt-2">
                      {ruleResults.map((rule) => (
                        <div key={rule.label} className="flex items-center gap-2">
                          {rule.passed ? (
                            <CheckCircle size={14} className="text-emerald-500" />
                          ) : (
                            <XCircle size={14} className="text-slate-300" />
                          )}
                          <span className={`text-xs ${rule.passed ? 'text-emerald-600' : 'text-slate-500'}`}>
                            {rule.label}
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </div>

              {/* Confirm Password */}
              <div className="space-y-2">
                <Label htmlFor="cp-confirm">确认新密码</Label>
                <div className="relative">
                  <Input
                    id="cp-confirm"
                    type={showConfirm ? 'text' : 'password'}
                    value={confirmPassword}
                    onChange={(e) => setConfirmPassword(e.target.value)}
                    placeholder="请再次输入新密码"
                  />
                  <button
                    type="button"
                    onClick={() => setShowConfirm(!showConfirm)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
                  >
                    {showConfirm ? <EyeOff size={16} /> : <Eye size={16} />}
                  </button>
                </div>
                {confirmPassword.length > 0 && newPassword !== confirmPassword && (
                  <p className="text-xs text-red-500 flex items-center gap-1 mt-1">
                    <XCircle size={12} />
                    两次输入的密码不一致
                  </p>
                )}
                {confirmPassword.length > 0 && newPassword === confirmPassword && newPassword.length > 0 && (
                  <p className="text-xs text-emerald-500 flex items-center gap-1 mt-1">
                    <CheckCircle size={12} />
                    密码匹配
                  </p>
                )}
              </div>

              {/* Error / Success Messages */}
              {error && (
                <div className="flex items-center gap-2 text-sm text-red-600 bg-red-50 px-3 py-2 rounded-lg">
                  <XCircle size={14} />
                  {error}
                </div>
              )}
              {success && (
                <div className="flex items-center gap-2 text-sm text-emerald-600 bg-emerald-50 px-3 py-2 rounded-lg">
                  <CheckCircle size={14} />
                  {success}
                </div>
              )}

              {/* Submit */}
              <Button
                className="w-full inline-flex items-center gap-2"
                onClick={handleSubmit}
                disabled={submitting}
              >
                <ShieldCheck size={16} />
                {submitting ? '提交中...' : '确认修改密码'}
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
