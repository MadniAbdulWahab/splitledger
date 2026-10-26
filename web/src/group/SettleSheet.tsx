import { useEffect, useState } from 'react';
import type { SuggestedPayment } from '../api';
import { formatIban } from '../format';
import { newIdempotencyKey } from '../ids';
import { useSingleFlight } from '../ui/once';
import { Icon } from '../ui/Icon';
import { Avatar, Empty, Money, Sheet } from '../ui/parts';
import { useToast } from '../ui/toast';
import { useGroup } from './context';

const pairKey = (d: SuggestedPayment) => `${d.fromMemberId}-${d.toMemberId}`;

/**
 * Settling up: pick an open amount, pay it with the GiroCode in your banking app, mark it as paid.
 * First everything between you and the others, then what is open between the others. The
 * simplified plan (fewest transfers for the whole group) is offered when it saves transfers.
 */
export function SettleSheet({ initial, onClose }: { initial?: SuggestedPayment; onClose: () => void }) {
  const { data, myMemberId } = useGroup();
  const [selected, setSelected] = useState<SuggestedPayment | null>(initial ?? null);
  const [done, setDone] = useState<SuggestedPayment | null>(null);
  const [showSimplified, setShowSimplified] = useState(false);
  const mine = data.debts.filter((d) => d.fromMemberId === myMemberId || d.toMemberId === myMemberId);
  const others = data.debts.filter((d) => d.fromMemberId !== myMemberId && d.toMemberId !== myMemberId);
  const fewer = data.simplified.length > 0 && data.simplified.length < data.debts.length;

  if (done) {
    return (
      <Sheet title="Settled" onClose={onClose}>
        <div className="settled">
          <span className="check"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round"><path d="m5 12.5 4.5 4.5L19 7" /></svg></span>
          <h2>{phrase(done, myMemberId, true)}</h2>
          <p className="muted"><Money cents={done.amountCents} /> recorded. Everyone in the group sees it in the activity.</p>
          <button type="button" className="btn btn-primary btn-block" onClick={onClose}>Done</button>
        </div>
      </Sheet>
    );
  }

  if (selected) {
    return (
      <Sheet title="Settle up" onClose={onClose}>
        <PaymentDetail debt={selected} onBack={initial ? undefined : () => setSelected(null)} onPaid={() => setDone(selected)} />
      </Sheet>
    );
  }

  return (
    <Sheet title="Settle up" onClose={onClose}>
      <div className="stack">
        {data.debts.length === 0
          ? <Empty emoji="🎉" title="Everyone is settled" text="Nobody owes anybody anything right now." />
          : (
            <>
              <DebtList title="You" debts={mine} empty="You are settled with everyone." onPick={setSelected} />
              {others.length > 0 && <DebtList title="Between the others" debts={others} onPick={setSelected} />}
            </>
          )}
        {fewer && (
          <div className="banner">
            <div className="grow">
              <strong>Fewer transfers</strong>
              <p className="small muted">The group can settle everything with {data.simplified.length} transfers instead of {data.debts.length}. The amounts change, the end result is the same.</p>
            </div>
            <button type="button" className="btn btn-quiet btn-small" onClick={() => setShowSimplified((v) => !v)}>
              {showSimplified ? 'Hide' : 'Show'}
            </button>
          </div>
        )}
        {showSimplified && <DebtList title="With fewer transfers" debts={data.simplified} onPick={setSelected} />}
      </div>
    </Sheet>
  );
}

function phrase(d: SuggestedPayment, me: number, past = false): string {
  if (d.fromMemberId === me) return past ? `You paid ${d.toName}` : `You owe ${d.toName}`;
  if (d.toMemberId === me) return past ? `${d.fromName} paid you` : `${d.fromName} owes you`;
  return past ? `${d.fromName} paid ${d.toName}` : `${d.fromName} owes ${d.toName}`;
}

function DebtList({ title, debts, empty, onPick }: {
  title: string; debts: SuggestedPayment[]; empty?: string; onPick: (d: SuggestedPayment) => void;
}) {
  const { myMemberId } = useGroup();
  return (
    <section className="section">
      <h3 className="muted small">{title}</h3>
      {debts.length === 0 ? <p className="muted small">{empty}</p> : (
        <ul className="list">
          {debts.map((d) => {
            const other = d.fromMemberId === myMemberId ? d.toMemberId : d.fromMemberId;
            const otherName = d.fromMemberId === myMemberId ? d.toName : d.fromName;
            const tone = d.fromMemberId === myMemberId ? 'owe' : d.toMemberId === myMemberId ? 'owed' : '';
            return (
              <li key={pairKey(d)}>
                <button type="button" className="row clickable" onClick={() => onPick(d)}>
                  <Avatar id={other} name={otherName} />
                  <span className="grow"><span className="title">{phrase(d, myMemberId)}</span></span>
                  <Money cents={d.amountCents} className={tone} />
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

function PaymentDetail({ debt, onBack, onPaid }: { debt: SuggestedPayment; onBack?: () => void; onPaid: () => void }) {
  const toast = useToast();
  const { api, groupId, myMemberId, data, run } = useGroup();
  const [qr, setQr] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => () => { if (qr) URL.revokeObjectURL(qr); }, [qr]);

  const iOwe = debt.fromMemberId === myMemberId;
  const theyOweMe = debt.toMemberId === myMemberId;
  const myIban = data.members.find((m) => m.id === myMemberId)?.iban ?? null;
  const receiverIban = theyOweMe ? myIban : debt.toIban;

  // For typing the transfer by hand instead of scanning: without spaces, which every banking app takes.
  async function copyIban(iban: string) {
    try {
      await navigator.clipboard.writeText(iban.replace(/\s/g, ''));
      toast('IBAN copied');
    } catch {
      toast('Copying is blocked here; press and hold the IBAN to select it.', 'error');
    }
  }

  // Like the expense sheet: one key per screen and a lock, so a double tap records one payment.
  const [requestKey] = useState(newIdempotencyKey);
  const markPaid = useSingleFlight(async () => {
    setBusy(true);
    const ok = await run(() => api.recordPayment(groupId, debt, requestKey));
    setBusy(false);
    if (ok) onPaid();
  });

  return (
    <div className="stack">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 12 }}>
        <Avatar id={debt.fromMemberId} name={debt.fromName} size="large" />
        <Icon name="settle" />
        <Avatar id={debt.toMemberId} name={debt.toName} size="large" />
      </div>
      <div style={{ textAlign: 'center', display: 'grid', gap: 4 }}>
        <p className="muted">{iOwe ? `You pay ${debt.toName}` : theyOweMe ? `${debt.fromName} pays you` : `${debt.fromName} pays ${debt.toName}`}</p>
        <Money cents={debt.amountCents} big />
        {receiverIban ? (
          <div className="iban-line">
            <span className="muted small">to <span className="iban">{formatIban(receiverIban)}</span></span>
            <button type="button" className="btn btn-quiet btn-small" onClick={() => void copyIban(receiverIban)}>
              <Icon name="copy" small />Copy IBAN
            </button>
          </div>
        ) : <p className="muted small">{debt.toName} has not added an IBAN yet.</p>}
      </div>

      {receiverIban && (
        qr ? (
          <div className="qr">
            <img src={qr} alt={`GiroCode for ${debt.amountCents / 100} euros to ${debt.toName}`} />
            <p className="small muted">
              {iOwe ? 'Scan it in your banking app (photo transfer / GiroCode), check name, IBAN and amount, send it, then mark it as paid.'
                : theyOweMe ? `Let ${debt.fromName} scan this with their banking app. Mark it as received when the money is in your account.`
                  : `${debt.fromName} scans this with their banking app.`}
            </p>
          </div>
        ) : (
          <button type="button" className="btn btn-quiet btn-block"
            onClick={() => api.giroCode(groupId, debt).then(setQr, (e: Error) => setError(e.message))}>
            Show GiroCode
          </button>
        )
      )}
      {error && <p className="error-text" role="alert">{error}</p>}
      <button type="button" className="btn btn-primary btn-block" disabled={busy} onClick={() => void markPaid()}>
        {theyOweMe ? 'Mark as received' : 'Mark as paid'}
      </button>
      {onBack && <button type="button" className="btn btn-link" onClick={onBack}>All open amounts</button>}
    </div>
  );
}
