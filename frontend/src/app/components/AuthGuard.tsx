import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { isAuthenticated } from '../lib/auth';
import { Zap } from 'lucide-react';

export function AuthGuard({ children }: { children: React.ReactNode }) {
  const navigate = useNavigate();
  const [checking, setChecking] = useState(true);

  useEffect(() => {
    if (!isAuthenticated()) {
      navigate('/login', { replace: true });
    } else {
      setChecking(false);
    }
  }, [navigate]);

  if (checking) {
    return (
      <div className="flex h-screen items-center justify-center bg-[#F8FAFC]">
        <div className="flex flex-col items-center gap-4">
          <div className="w-10 h-10 bg-gradient-to-br from-blue-600 to-indigo-600 rounded-lg flex items-center justify-center shadow-sm animate-pulse">
            <Zap size={20} className="text-white" />
          </div>
          <p className="text-sm text-slate-400">加载中...</p>
        </div>
      </div>
    );
  }

  return <>{children}</>;
}
