import { useState, useEffect } from 'react';
import type { ReactNode } from 'react';
import { Dialog, DialogContent, DialogTitle } from './dialog';

// A reusable view / edit modal driven by a field config. Used across the ERP
// and advertising management pages so row-level 查看 / 编辑 buttons share one
// consistent, accessible implementation.

export interface RecordField {
  key: string;
  label: string;
  /** Input kind in edit mode. 'readonly' shows the value but is never editable. */
  type?: 'text' | 'number' | 'select' | 'textarea' | 'readonly';
  options?: { value: string; label: string }[];
  /** Optional formatter for the value shown in view mode. */
  format?: (value: any, record: any) => ReactNode;
  /** Hide this field entirely in view mode. */
  editOnly?: boolean;
  /** Hide this field entirely in edit mode. */
  viewOnly?: boolean;
  placeholder?: string;
}

interface RecordModalProps {
  open: boolean;
  mode: 'view' | 'edit';
  title: string;
  fields: RecordField[];
  record: Record<string, any> | null;
  onClose: () => void;
  /** Called in edit mode with the edited field values. May be async. */
  onSave?: (values: Record<string, any>) => void | Promise<void>;
  saveLabel?: string;
}

export function RecordModal({
  open,
  mode,
  title,
  fields,
  record,
  onClose,
  onSave,
  saveLabel = '保存',
}: RecordModalProps) {
  const [form, setForm] = useState<Record<string, any>>({});
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (open && record) {
      const init: Record<string, any> = {};
      for (const f of fields) {
        init[f.key] = record[f.key] ?? '';
      }
      setForm(init);
      setError(null);
    }
  }, [open, record, fields]);

  if (!open || !record) return null;

  const isView = mode === 'view';
  const visibleFields = fields.filter((f) => (isView ? !f.editOnly : !f.viewOnly));

  async function handleSave() {
    if (!onSave) return;
    setSaving(true);
    setError(null);
    try {
      await onSave(form);
    } catch (e: any) {
      setError(e?.message || '保存失败，请重试。');
      setSaving(false);
      return;
    }
    setSaving(false);
  }

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o && !saving) onClose(); }}>
      <DialogContent className="block gap-0 p-0 w-full sm:max-w-lg max-h-[85vh] overflow-y-auto rounded-xl border border-slate-200 bg-white shadow-xl">
        <div className="flex items-center justify-between px-5 py-4 border-b border-slate-100 sticky top-0 bg-white">
          <DialogTitle className="text-base font-semibold text-slate-900">{title}</DialogTitle>
        </div>

        <div className="px-5 py-4 space-y-3">
          {visibleFields.map((f) => {
            const value = isView ? record[f.key] : form[f.key];
            if (isView || f.type === 'readonly') {
              const display = f.format ? f.format(record[f.key], record) : (record[f.key] ?? '—');
              return (
                <div key={f.key} className="flex items-start justify-between gap-4 py-1.5 border-b border-slate-50 last:border-0">
                  <span className="text-xs font-medium text-slate-500 shrink-0 pt-0.5">{f.label}</span>
                  <span className="text-sm text-slate-800 text-right break-words">
                    {display === '' || display == null ? '—' : display}
                  </span>
                </div>
              );
            }
            return (
              <div key={f.key}>
                <label className="block text-xs font-medium text-slate-500 mb-1">{f.label}</label>
                {f.type === 'select' ? (
                  <select
                    value={value ?? ''}
                    onChange={(e) => setForm({ ...form, [f.key]: e.target.value })}
                    className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400"
                  >
                    {(f.options ?? []).map((o) => (
                      <option key={o.value} value={o.value}>{o.label}</option>
                    ))}
                  </select>
                ) : f.type === 'textarea' ? (
                  <textarea
                    value={value ?? ''}
                    rows={3}
                    placeholder={f.placeholder}
                    onChange={(e) => setForm({ ...form, [f.key]: e.target.value })}
                    className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 resize-none"
                  />
                ) : (
                  <input
                    type={f.type === 'number' ? 'number' : 'text'}
                    value={value ?? ''}
                    placeholder={f.placeholder}
                    onChange={(e) => setForm({ ...form, [f.key]: e.target.value })}
                    className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400"
                  />
                )}
              </div>
            );
          })}
          {error && <p className="text-xs text-red-600">{error}</p>}
        </div>

        <div className="flex items-center justify-end gap-2 px-5 py-4 border-t border-slate-100">
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm font-medium text-slate-600 bg-white border border-slate-200 rounded-lg hover:bg-slate-50"
          >
            {isView ? '关闭' : '取消'}
          </button>
          {!isView && (
            <button
              onClick={handleSave}
              disabled={saving}
              className="px-4 py-2 text-sm font-medium text-white bg-indigo-600 rounded-lg hover:bg-indigo-700 disabled:opacity-60"
            >
              {saving ? '保存中…' : saveLabel}
            </button>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}
