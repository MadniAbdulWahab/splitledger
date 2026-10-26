import { useEffect, useId, useRef, type ReactNode } from 'react';
import { initials, memberColour } from '../avatar';
import { categoryOf } from '../categories';
import { formatCents } from '../money';
import { Icon } from './Icon';
import { useSwipeToClose } from './swipe';

/**
 * An amount with the euros in front and the cents and € sign set back, e.g. 87,00 €. The spans
 * still read as one text, so screen readers and copy-paste get "87,00 €".
 */
export function Money({ cents, big, signed, className = '' }: { cents: number; big?: boolean; signed?: boolean; className?: string }) {
  // signed: +12,50 € / −10,00 € (a real minus sign), for changes rather than amounts.
  const sign = signed ? (cents > 0 ? '+' : cents < 0 ? '−' : '') : '';
  const text = sign + formatCents(signed ? Math.abs(cents) : cents).replace(/\s/g, ' ');
  const match = /^([-+−]?[\d.]+)(,\d\d) €$/.exec(text);
  return (
    <span className={`money ${big ? 'big' : ''} ${className}`}>
      {match ? <>{match[1]}<span className="cents">{match[2]}</span><span className="cur"> €</span></> : text}
    </span>
  );
}

export function Avatar({ id, name, size }: { id: number; name: string; size?: 'small' | 'large' }) {
  return (
    <span className={`avatar ${size ?? ''}`} style={{ background: memberColour(id) }} title={name} aria-hidden="true">
      {initials(name)}
    </span>
  );
}

export function CategoryTile({ category }: { category: string | null }) {
  const info = categoryOf(category);
  return <span className="tile" title={info.label} aria-hidden="true">{info.emoji}</span>;
}

export function GroupMark({ name }: { name: string }) {
  return <span className="group-mark" aria-hidden="true">{initials(name)}</span>;
}

export function Field({ label, hint, error, children }: { label: string; hint?: string; error?: string | null; children: ReactNode }) {
  return (
    <label className="field">
      <span className="label">{label}</span>
      {children}
      {error ? <span className="error-text">{error}</span> : hint && <span className="hint">{hint}</span>}
    </label>
  );
}

export function Empty({ emoji, title, text, action }: { emoji: string; title: string; text: string; action?: ReactNode }) {
  return (
    <div className="empty">
      <span className="tile" aria-hidden="true">{emoji}</span>
      <h3>{title}</h3>
      <p className="muted small">{text}</p>
      {action}
    </div>
  );
}

export function SkeletonRows({ count = 3 }: { count?: number }) {
  return (
    <ul className="list" aria-busy="true" aria-label="Loading">
      {Array.from({ length: count }, (_, i) => (
        <li key={i} className="row">
          <span className="skeleton" style={{ width: 42, height: 42, borderRadius: 13 }} />
          <span className="grow"><span className="skeleton" style={{ width: '60%', height: 14 }} />
            <span className="skeleton" style={{ width: '35%', height: 12, marginTop: 6 }} /></span>
        </li>
      ))}
    </ul>
  );
}

/** Bottom sheet on phones, a dialog on wide screens. Escape, a tap outside and a swipe down close it. */
export function Sheet({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const titleId = useId();
  const sheet = useRef<HTMLDivElement>(null);
  useSwipeToClose(sheet, onClose);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = overflow;
    };
  }, [onClose]);

  return (
    <div className="sheet-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div ref={sheet} className="sheet" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <div className="sheet-handle" />
        <div className="sheet-head">
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="close" aria-label="Close" onClick={onClose}><Icon name="close" small /></button>
        </div>
        {children}
      </div>
    </div>
  );
}

/** The fine line pattern of banknotes and bank cards (a guilloche), drawn once as SVG. */
export function Guilloche() {
  const curves = Array.from({ length: 18 }, (_, i) => {
    const r = 40 + i * 7;
    const wobble = 10 + (i % 3) * 4;
    const points = Array.from({ length: 73 }, (_, k) => {
      const a = (k / 72) * Math.PI * 2;
      const rr = r + wobble * Math.sin(a * 6 + i * 0.5);
      return `${(170 + rr * Math.cos(a)).toFixed(1)},${(170 + rr * Math.sin(a)).toFixed(1)}`;
    });
    return <polyline key={i} points={points.join(' ')} />;
  });
  return (
    <svg className="guilloche" viewBox="0 0 340 340" fill="none" stroke="currentColor" strokeWidth="0.8" aria-hidden="true">
      {curves}
    </svg>
  );
}
