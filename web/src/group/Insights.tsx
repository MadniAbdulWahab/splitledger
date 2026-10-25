import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { Insights as InsightsData } from '../api';
import { categoryOf } from '../categories';
import { columnPath, compactEuro, niceTicks } from '../chart';
import { formatMonth, formatMonthShort } from '../format';
import { formatCents } from '../money';
import { Avatar, Empty, Money, SkeletonRows } from '../ui/parts';
import { useGroup } from './context';

const RANGES = [3, 6, 12] as const;

/** Where the group's money went: per month (group and you), per category, and who paid. */
export function Insights() {
  const { api, groupId, version, nameOf } = useGroup();
  const [months, setMonths] = useState<number>(6);
  const [data, setData] = useState<InsightsData | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.insights(groupId, months).then(setData, (e: Error) => setError(e.message));
  }, [api, groupId, months, version]);

  return (
    <>
      <div className="section-head" style={{ alignItems: 'center', padding: 0 }}>
        <h1>Insights</h1>
        <div className="segmented" role="group" aria-label="Period" style={{ width: 180 }}>
          {RANGES.map((r) => <button type="button" key={r} aria-pressed={months === r} onClick={() => setMonths(r)}>{r} mo</button>)}
        </div>
      </div>
      {error && <p className="error-text" role="alert">{error}</p>}
      {!data ? <SkeletonRows count={4} /> : data.totalCents === 0 ? (
        <div className="list"><Empty emoji="📊" title="Nothing to show yet" text={`No expenses in the last ${months} months. Once there are, you see here where the money went.`} /></div>
      ) : (
        <>
          <div className="stat-row">
            <div className="stat"><span className="muted small">Group spent</span><Money cents={data.totalCents} /></div>
            <div className="stat"><span className="muted small">Your share</span><Money cents={data.myShareCents} /></div>
          </div>
          <MonthChart data={data} />
          <section className="chart-card" aria-label="By category">
            <h2>By category</h2>
            <Bars rows={data.categories.map((c) => {
              const info = categoryOf(c.category);
              return {
                key: c.category, value: c.totalCents, label: `${info.emoji} ${info.label}`,
                sub: `${c.expenses} ${c.expenses === 1 ? 'expense' : 'expenses'}, your share ${formatCents(c.myShareCents)}`,
              };
            })} />
          </section>
          <section className="chart-card" aria-label="Who paid">
            <h2>Who paid</h2>
            <Bars rows={data.payers.map((p) => ({
              key: String(p.memberId), value: p.paidCents, label: nameOf(p.memberId),
              lead: <Avatar id={p.memberId} name={nameOf(p.memberId)} size="small" />,
            }))} />
          </section>
        </>
      )}
    </>
  );
}

/**
 * Columns per month, the group's total next to your share; hover or tap a month for the numbers.
 * Hover follows only a real mouse: after a tap, phones send a "mouse leave" that would hide it again.
 */
function MonthChart({ data }: { data: InsightsData }) {
  const [active, setActive] = useState<number | null>(null);
  // Drawn at the container's real width, so 12px labels stay 12px on a phone.
  const box = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(600);
  useEffect(() => {
    const element = box.current;
    if (!element) return undefined;
    const observer = new ResizeObserver(([entry]) => setWidth(Math.max(240, Math.round(entry!.contentRect.width))));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  const height = 200;
  const left = 52;
  const bottom = 28;
  const top = 8;
  const max = Math.max(...data.months.map((m) => m.totalCents));
  const ticks = niceTicks(max);
  const scaleMax = ticks[ticks.length - 1]!;
  const y = (cents: number) => top + (height - top - bottom) * (1 - cents / scaleMax);
  const band = (width - left) / data.months.length;
  const column = Math.min(24, (band - 14) / 2);
  const hovered = active === null ? null : data.months[active]!;
  const tipX = active === null ? 0 : left + active * band + band / 2;
  // Months near the edges get a tooltip that opens inwards (about 180px wide).
  const tipSide = tipX < 100 ? 'start' : tipX > width - 100 ? 'end' : '';
  // When months get narrow, every other label is left out; the latest month always keeps its label.

  return (
    <section className="chart-card" aria-label="Spending per month">
      <h2>Per month</h2>
      <div className="legend">
        <span><i className="swatch" style={{ background: 'var(--series-1)' }} />Group</span>
        <span><i className="swatch" style={{ background: 'var(--series-2)' }} />Your share</span>
      </div>
      <div ref={box} style={{ position: 'relative' }} onPointerLeave={(e) => { if (e.pointerType === 'mouse') setActive(null); }}>
        <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Spending per month, group and your share">
          {ticks.map((t) => (
            <g key={t}>
              <line x1={left} x2={width} y1={y(t)} y2={y(t)} stroke="var(--line)" strokeWidth="1" />
              <text x={left - 8} y={y(t)} dy="0.32em" textAnchor="end" fontSize="12" fill="var(--muted)">{compactEuro(t)}</text>
            </g>
          ))}
          {data.months.map((m, i) => {
            const x = left + i * band + (band - column * 2 - 2) / 2;
            return (
              <g key={m.month} opacity={active === null || active === i ? 1 : 0.45}>
                <path d={columnPath(x, column, y(m.totalCents), y(0))} fill="var(--series-1)" />
                <path d={columnPath(x + column + 2, column, y(m.myShareCents), y(0))} fill="var(--series-2)" />
                {(band >= 36 || (data.months.length - 1 - i) % 2 === 0) && (
                  <text x={left + i * band + band / 2} y={height - 8} textAnchor="middle" fontSize="12" fill="var(--muted)">
                    {formatMonthShort(m.month)}
                  </text>
                )}
                {/* The hover target is the whole month, much bigger than the columns. */}
                <rect x={left + i * band} y={top} width={band} height={height - top} fill="transparent"
                  onPointerEnter={(e) => { if (e.pointerType === 'mouse') setActive(i); }} onClick={() => setActive(i)} />
              </g>
            );
          })}
        </svg>
        {hovered && (
          <div className={`chart-tip ${tipSide}`} style={{
            left: `${(tipX / width) * 100}%`,
            top: `${(y(hovered.totalCents) / height) * 100}%`,
          }}>
            {formatMonth(hovered.month)}<br />Group {formatCents(hovered.totalCents)}, you {formatCents(hovered.myShareCents)}
          </div>
        )}
      </div>
      <details className="more">
        <summary>Show as table</summary>
        <table className="small num" style={{ width: '100%', borderCollapse: 'collapse', marginTop: 8 }}>
          <thead><tr><th style={{ textAlign: 'left' }}>Month</th><th style={{ textAlign: 'right' }}>Group</th><th style={{ textAlign: 'right' }}>You</th></tr></thead>
          <tbody>
            {data.months.map((m) => (
              <tr key={m.month}><td>{formatMonth(m.month)}</td><td style={{ textAlign: 'right' }}>{formatCents(m.totalCents)}</td>
                <td style={{ textAlign: 'right' }}>{formatCents(m.myShareCents)}</td></tr>
            ))}
          </tbody>
        </table>
      </details>
    </section>
  );
}

/** Horizontal bars, one series, every value labelled at the end. */
function Bars({ rows }: { rows: { key: string; value: number; label: string; sub?: string; lead?: ReactNode }[] }) {
  const max = Math.max(...rows.map((r) => r.value), 1);
  return (
    <div className="stack" style={{ gap: 14 }}>
      {rows.map((r) => (
        <div key={r.key} className="bar-row">
          <span style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
            {r.lead}<span style={{ fontWeight: 600 }}>{r.label}</span>
          </span>
          <Money cents={r.value} />
          <span className="bar-track"><span className="bar-fill" style={{ width: `${(r.value / max) * 100}%`, background: 'var(--series-1)' }} /></span>
          {r.sub && <span className="muted tiny" style={{ gridColumn: '1 / -1', marginTop: -4 }}>{r.sub}</span>}
        </div>
      ))}
    </div>
  );
}
