import { useState } from 'react';
import type { ImportReport } from '../api';
import { formatShortDate } from '../format';
import { guessCategory } from '../categories';
import { Icon } from '../ui/Icon';
import { CategoryTile, Empty, Money } from '../ui/parts';
import { useToast } from '../ui/toast';
import { useGroup } from './context';

/**
 * Import instead of typing: the CSV export of a Sparkasse account. Outgoing payments wait here,
 * visible only to you, until you share or ignore them; importing the same file again adds nothing.
 */
export function Bank() {
  const { api, groupId, data, run, openExpense } = useGroup();
  const toast = useToast();
  const [over, setOver] = useState(false);

  async function upload(file: File | undefined) {
    if (!file) return;
    let r: ImportReport | null = null;
    // The message comes after the list has reloaded, so it never contradicts what is on screen.
    if (await run(async () => { r = await api.importStatement(groupId, file); }) && r) {
      const report: ImportReport = r;
      toast(report.newTransactions === 0
        ? `Nothing new: ${report.duplicates} already imported, ${report.skippedCount} skipped (incoming or pending)`
        : `${report.newTransactions} new ${report.newTransactions === 1 ? 'payment' : 'payments'} to sort out, ${report.duplicates} already imported`);
    }
  }

  return (
    <>
      <h1>Bank import</h1>
      <label className={`dropzone ${over ? 'over' : ''}`}
        onDragOver={(e) => { e.preventDefault(); setOver(true); }} onDragLeave={() => setOver(false)}
        onDrop={(e) => { e.preventDefault(); setOver(false); void upload(e.dataTransfer.files[0]); }}>
        <input type="file" accept=".csv,text/csv" aria-label="Choose a CSV file" onChange={(e) => {
          const file = e.target.files?.[0];
          e.target.value = '';
          void upload(file);
        }} />
        <span className="tile" aria-hidden="true"><Icon name="upload" /></span>
        <strong>Choose your statement (CSV)</strong>
        <span className="muted small">Sparkasse online banking: Umsätze, Export, CSV-CAMT. Only you see the payments until you share one.</span>
      </label>

      <section className="section">
        <div className="section-head"><h2>To sort out</h2>{data.candidates.length > 0 && <span className="muted small">{data.candidates.length} open</span>}</div>
        {data.candidates.length === 0
          ? <div className="list"><Empty emoji="🏦" title="All sorted" text="Imported payments land here. Share the ones that were for the group, ignore the rest." /></div>
          : (
            <ul className="list">
              {data.candidates.map((t) => (
                <li key={t.id} className="row" style={{ flexWrap: 'wrap' }}>
                  <CategoryTile category={guessCategory(t.counterpartyName, t.purpose)} />
                  <span className="grow">
                    <span className="title">{t.counterpartyName ?? 'Unknown payee'}</span>
                    <span className="sub">{formatShortDate(t.bookingDate)}{t.purpose ? `, ${t.purpose}` : ''}</span>
                  </span>
                  <Money cents={-t.amountCents} />
                  <span style={{ display: 'flex', gap: 8, width: '100%', paddingLeft: 54 }}>
                    <button type="button" className="btn btn-primary btn-small" onClick={() => openExpense({ kind: 'bank', transaction: t })}>Share</button>
                    <button type="button" className="btn btn-quiet btn-small" onClick={() => void run(() => api.ignore(groupId, t.id), 'Ignored')}>Ignore</button>
                  </span>
                </li>
              ))}
            </ul>
          )}
      </section>
    </>
  );
}
