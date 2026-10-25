import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { Icon } from './Icon';

interface Toast { id: number; text: string; kind: 'info' | 'error' }

const ToastContext = createContext<(text: string, kind?: Toast['kind']) => void>(() => {});

/** Shows a short message: "Expense added". Errors stay until closed, everything else for 4 seconds. */
export const useToast = () => useContext(ToastContext);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  // A new message replaces the previous one; only errors stay until they are closed.
  const show = useCallback((text: string, kind: Toast['kind'] = 'info') => {
    setToasts((current) => [...current.filter((t) => t.kind === 'error').slice(-1), { id: Date.now() + Math.random(), text, kind }]);
  }, []);
  const close = useCallback((id: number) => setToasts((current) => current.filter((t) => t.id !== id)), []);

  return (
    <ToastContext.Provider value={show}>
      {children}
      <div className="toasts">
        {toasts.map((t) => <ToastView key={t.id} toast={t} onClose={() => close(t.id)} />)}
      </div>
    </ToastContext.Provider>
  );
}

function ToastView({ toast, onClose }: { toast: Toast; onClose: () => void }) {
  useEffect(() => {
    if (toast.kind === 'error') return undefined;
    const timer = setTimeout(onClose, 4000);
    return () => clearTimeout(timer);
  }, [toast.kind, onClose]);

  return (
    <div className={`toast ${toast.kind}`} role={toast.kind === 'error' ? 'alert' : 'status'}>
      <span>{toast.text}</span>
      <button type="button" className="close" aria-label="Dismiss" onClick={onClose}><Icon name="close" small /></button>
    </div>
  );
}
