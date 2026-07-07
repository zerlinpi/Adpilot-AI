import { Link } from 'react-router';
import {
  Bell,
  Database,
  KeyRound,
  Lock,
  ShieldCheck,
  Sparkles,
  Store,
  Users,
} from 'lucide-react';
import { ErpPageHeader } from '../components/erp/ErpPageHeader';

const settingLinks = [
  {
    title: '店铺设置',
    description: '创建、连接并管理销售店铺',
    href: '/stores',
    icon: Store,
  },
  {
    title: 'AI 接口配置',
    description: '配置 AI Provider、模型与密钥',
    href: '/settings/ai',
    icon: Sparkles,
  },
  {
    title: 'API 连接',
    description: '管理平台连接与同步授权',
    href: '/api-connections',
    icon: Database,
  },
  {
    title: '飞书机器人',
    description: '绑定飞书应用与消息通知',
    href: '/integrations/feishu',
    icon: Bell,
  },
  {
    title: '用户管理',
    description: '维护账号、状态与店铺分配',
    href: '/users',
    icon: Users,
  },
  {
    title: '角色权限',
    description: '配置角色、权限与数据范围',
    href: '/roles',
    icon: ShieldCheck,
  },
  {
    title: '个人中心',
    description: '查看并维护当前账号资料',
    href: '/profile',
    icon: KeyRound,
  },
  {
    title: '修改密码',
    description: '更新当前账号登录密码',
    href: '/change-password',
    icon: Lock,
  },
];

export function SettingsPage() {
  return (
    <div className="space-y-4">
      <ErpPageHeader title="系统设置" description="账号、权限、店铺和集成配置" />

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        {settingLinks.map((item) => {
          const Icon = item.icon;
          return (
            <Link
              key={item.href}
              to={item.href}
              className="group rounded-lg border border-slate-200 bg-white p-4 transition-colors hover:border-blue-200 hover:bg-blue-50/40"
            >
              <div className="flex items-start gap-3">
                <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-slate-100 text-slate-600 group-hover:bg-blue-100 group-hover:text-blue-700">
                  <Icon size={18} />
                </div>
                <div className="min-w-0">
                  <h2 className="text-sm font-semibold text-slate-900">{item.title}</h2>
                  <p className="mt-1 text-xs leading-5 text-slate-500">{item.description}</p>
                </div>
              </div>
            </Link>
          );
        })}
      </div>
    </div>
  );
}
