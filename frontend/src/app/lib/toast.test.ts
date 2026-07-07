import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock sonner so the wrapper can be tested without a DOM/Toaster mounted.
vi.mock('sonner', () => {
  const toast: any = vi.fn();
  toast.success = vi.fn();
  toast.error = vi.fn();
  toast.loading = vi.fn();
  return { toast };
});

import { toast as sonnerToast } from 'sonner';
import { notify } from './toast';

describe('notify (unified toast wrapper)', () => {
  beforeEach(() => vi.clearAllMocks());

  it('success forwards message + description to sonner.success', () => {
    notify.success('已切换店铺', '美国旗舰店');
    expect((sonnerToast as any).success).toHaveBeenCalledWith('已切换店铺', { description: '美国旗舰店' });
  });

  it('omits the options object when no description is given', () => {
    notify.success('已保存');
    expect((sonnerToast as any).success).toHaveBeenCalledWith('已保存', undefined);
  });

  it('error surfaces the readable reason', () => {
    notify.error('发布失败', '凭据无效');
    expect((sonnerToast as any).error).toHaveBeenCalledWith('发布失败', { description: '凭据无效' });
  });

  it('info routes to the default sonner toast', () => {
    notify.info('已收到');
    expect(sonnerToast).toHaveBeenCalledWith('已收到', undefined);
  });

  it('loading routes to sonner.loading', () => {
    notify.loading('提交中…');
    expect((sonnerToast as any).loading).toHaveBeenCalledWith('提交中…', undefined);
  });
});
