import { useRouteError, useNavigate, isRouteErrorResponse } from 'react-router';
import { AlertTriangle, RefreshCw, Home } from 'lucide-react';

/**
 * Route-level error boundary. Wired as `errorElement` on the main layout route
 * so a single page throwing (e.g. unexpected data shape) renders a friendly
 * fallback inside the app shell instead of white-screening the entire SPA.
 */
export function RouteErrorBoundary() {
  const error = useRouteError();
  const navigate = useNavigate();

  let message = '页面加载时发生错误';
  if (isRouteErrorResponse(error)) {
    message = `${error.status} ${error.statusText}`;
  } else if (error instanceof Error) {
    message = error.message;
  } else if (typeof error === 'string') {
    message = error;
  }

  return (
    <div className="flex flex-col items-center justify-center min-h-[60vh] gap-4 px-6">
      <div className="w-14 h-14 rounded-full bg-red-100 flex items-center justify-center">
        <AlertTriangle size={28} className="text-red-600" />
      </div>
      <div className="text-center max-w-md">
        <p className="text-lg font-semibold text-slate-900">页面出现问题</p>
        <p className="text-sm text-slate-500 mt-1 break-words">{message}</p>
      </div>
      <div className="flex items-center gap-3">
        <button
          onClick={() => window.location.reload()}
          className="inline-flex items-center gap-2 px-4 py-2.5 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors shadow-sm"
        >
          <RefreshCw size={16} />
          重新加载
        </button>
        <button
          onClick={() => navigate('/')}
          className="inline-flex items-center gap-2 px-4 py-2.5 bg-white text-slate-700 text-sm font-medium rounded-lg border border-slate-200 hover:bg-slate-50 transition-colors"
        >
          <Home size={16} />
          返回首页
        </button>
      </div>
    </div>
  );
}
