import { Link } from 'react-router';
import { ShieldOff } from 'lucide-react';

export function ForbiddenPage() {
  return (
    <div className="flex h-screen items-center justify-center bg-[#F8FAFC]">
      <div className="flex flex-col items-center gap-6 text-center px-6">
        <div className="w-16 h-16 rounded-full bg-red-50 flex items-center justify-center">
          <ShieldOff size={32} className="text-red-400" />
        </div>
        <div>
          <h1 className="text-xl font-semibold text-slate-800 mb-2">无权限访问</h1>
          <p className="text-sm text-slate-500 max-w-sm">
            你没有权限查看当前页面，请联系管理员开通权限。
          </p>
        </div>
        <Link
          to="/"
          className="inline-flex items-center gap-2 px-5 py-2.5 bg-blue-600 text-white text-sm font-medium rounded-lg hover:bg-blue-700 transition-colors shadow-sm"
        >
          返回经营驾驶舱
        </Link>
      </div>
    </div>
  );
}
