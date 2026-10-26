import { useState } from 'react';
import type { ExpenseBody, Member, SplitMode } from '../api';
import { CATEGORIES, guessCategory, type Category } from '../categories';
import { formatDate } from '../format';
import { localToday, newIdempotencyKey } from '../ids';
import { formatCents, parseEuroToCents } from '../money';
import { distributeRest, splitEqually, splitProportionally, type Allocation } from '../split';
import { useFormSubmit } from '../ui/once';
import { Avatar, Field, Money, Sheet } from '../ui/parts';
import { useGroup, type Draft } from './context';

const SPLIT_MODES: { id: SplitMode; label: string; hint: string }[] = [
  { id: 'EQUAL', label: 'Equally', hint: 'Everyone selected pays the same.' },
  { id: 'EXACT', label: 'Amounts', hint: 'Enter what each person owes; it has to add up to the total.' },
  { id: 'SHARES', label: 'Shares', hint: 'E.g. 2 for someone who had two portions, 0 for someone who had none.' },
];

/** 1250 -> "12,50" for an input field (no currency sign, no thousands separator). */
export function formatPlain(cents: number): string {
  return `${Math.floor(cents / 100)},${String(cents % 100).padStart(2, '0')}`;
}

/** Form values for a new expense, a bank payment to share, or an expense to edit. */
function initialValues(draft: Draft, myMemberId: number, members: Member[]) {
  const blank = {
    description: '', amount: '', date: localToday(), payerId: myMemberId, mode: 'EQUAL' as SplitMode,
    excluded: [] as number[], exact: {} as Record<number, string>, shares: {} as Record<number, string>,
    notes: null as string | null, category: null as Category | null, hint: null as string | null,
  };
  if (draft.kind === 'bank') {
    const t = draft.transaction;
    return {
      ...blank, description: (t.counterpartyName ?? 'Bank payment').slice(0, 200), amount: formatPlain(-t.amountCents),
      date: t.bookingDate, hint: [t.counterpartyName, t.purpose].filter(Boolean).join(', '),
    };
  }
  if (draft.kind === 'edit') {
    const e = draft.expense;
    const shareOf = (id: number) => e.shares.find((s) => s.memberId === id);
    return {
      ...blank, description: e.description, amount: formatPlain(e.amountCents), date: e.expenseDate,
      payerId: e.payerId, mode: e.splitMode, notes: e.notes, category: e.category,
      excluded: e.splitMode === 'EQUAL' ? members.map((m) => m.id).filter((id) => !shareOf(id)) : [],
      exact: e.splitMode === 'EXACT'
        ? Object.fromEntries(e.shares.map((s) => [s.memberId, formatPlain(s.amountCents)])) : {},
      shares: e.splitMode === 'SHARES'
        ? Object.fromEntries(members.map((m) => [m.id, String(shareOf(m.id)?.shareUnits ?? 0)])) : {},
    };
  }
  return blank;
}

export function ExpenseSheet({ draft, onClose }: { draft: Draft; onClose: () => void }) {
  const { api, groupId, myMemberId, data, run, nameOf } = useGroup();
  const members = data.members;
  const [initial] = useState(() => initialValues(draft, myMemberId, members));
  const [description, setDescription] = useState(initial.description);
  const [amount, setAmount] = useState(initial.amount);
  const [date, setDate] = useState(initial.date);
  const [payerId, setPayerId] = useState(initial.payerId);
  const [mode, setMode] = useState<SplitMode>(initial.mode);
  const [excluded, setExcluded] = useState<number[]>(initial.excluded);
  const [exact, setExact] = useState<Record<number, string>>(initial.exact);
  const [shares, setShares] = useState<Record<number, string>>(initial.shares);
  // A chosen category wins; until then the page suggests one from what was typed.
  const [chosenCategory, setChosenCategory] = useState<Category | null | undefined>(draft.kind === 'edit' ? initial.category : undefined);
  const [busy, setBusy] = useState(false);
  const category = chosenCategory !== undefined ? chosenCategory
    : guessCategory(description, draft.kind === 'bank' ? draft.transaction.counterpartyName : null);
  const total = parseEuroToCents(amount);

  // What each person would owe, computed with the server's own rule (split.ts).
  const exactCents = new Map(members.map((m) => {
    const text = (exact[m.id] ?? '').trim();
    return [m.id, text === '' || text === '0' ? 0 : parseEuroToCents(text)] as const;
  }));
  const invalidExact = [...exactCents.values()].some((cents) => cents === null);
  const shareUnits = new Map(members.map((m) => {
    const text = (shares[m.id] ?? '1').trim();
    return [m.id, /^\d{1,4}$/.test(text) ? Number(text) : NaN] as const;
  }));
  const invalidShares = [...shareUnits.values()].some((units) => Number.isNaN(units) || units > 1000);

  let allocations: Allocation[] = [];
  if (total !== null) {
    if (mode === 'EQUAL') {
      allocations = splitEqually(total, members.map((m) => m.id).filter((id) => !excluded.includes(id)));
    } else if (mode === 'SHARES' && !invalidShares) {
      allocations = splitProportionally(total, members.map((m) => ({ memberId: m.id, weight: shareUnits.get(m.id) ?? 0 })));
    } else if (mode === 'EXACT' && !invalidExact) {
      allocations = members.map((m) => ({ memberId: m.id, cents: exactCents.get(m.id) ?? 0 })).filter((a) => a.cents > 0);
    }
  }
  const shareOf = (id: number) => allocations.find((a) => a.memberId === id)?.cents ?? 0;
  const assigned = allocations.reduce((sum, a) => sum + a.cents, 0);
  const remaining = total === null ? 0 : total - assigned;

  // Why the button is disabled, in words.
  let problem: string | null = null;
  if (total === null) problem = amount === '' ? 'Enter an amount.' : 'Use an amount like 12,50.';
  else if (!description.trim()) problem = 'Say what it was for.';
  else if (mode === 'EXACT' && invalidExact) problem = 'One of the amounts is not a valid number.';
  else if (mode === 'SHARES' && invalidShares) problem = 'Shares must be whole numbers from 0 to 1000.';
  else if (allocations.length === 0) problem = 'Choose who shares this expense.';
  else if (mode === 'EXACT' && remaining !== 0) {
    problem = remaining > 0 ? `${formatCents(remaining)} still has to be assigned.` : `${formatCents(-remaining)} too much assigned.`;
  } else if (allocations.every((a) => a.memberId === payerId)) {
    problem = `Only ${nameOf(payerId)} takes part, so nothing would be shared. Add someone else.`;
  }

  function splitRest() {
    if (total === null) return;
    const current = new Map(members.map((m) => {
      const text = (exact[m.id] ?? '').trim();
      return [m.id, text === '' ? null : exactCents.get(m.id) ?? null] as const;
    }));
    const filled = distributeRest(total, current);
    setExact(Object.fromEntries(members.map((m) => [m.id, formatPlain(filled.get(m.id) ?? 0)])));
  }

  // One key for as long as the sheet is open: a second tap repeats the same request, which the
  // server answers with the first expense instead of booking it again.
  const [requestKey] = useState(newIdempotencyKey);
  const submit = useFormSubmit(async () => {
    if (problem || total === null) return;
    const participants = mode === 'EQUAL'
      ? allocations.map((a) => ({ memberId: a.memberId }))
      : mode === 'EXACT'
        ? allocations.map((a) => ({ memberId: a.memberId, value: a.cents }))
        : members.filter((m) => (shareUnits.get(m.id) ?? 0) > 0).map((m) => ({ memberId: m.id, value: shareUnits.get(m.id) }));
    const body: ExpenseBody = {
      payerId, amountCents: total, description: description.trim(), notes: initial.notes, expenseDate: date,
      splitMode: mode, participants, category,
    };
    setBusy(true);
    const ok = draft.kind === 'bank'
      ? await run(() => api.confirm(groupId, draft.transaction.id, body), `Shared “${body.description}” with the group`)
      : draft.kind === 'edit'
        ? await run(() => api.editExpense(groupId, draft.expense.id, body, requestKey), 'Expense updated')
        : await run(() => api.addExpense(groupId, body, requestKey), 'Expense added');
    setBusy(false);
    if (ok) onClose();
  });

  const title = draft.kind === 'bank' ? 'Share a bank payment' : draft.kind === 'edit' ? 'Edit expense' : 'Add expense';
  return (
    <Sheet title={title} onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        {initial.hint && <p className="hint">From your statement on {formatDate(initial.date)}: {initial.hint}. Change anything that differs, e.g. if only part was shared.</p>}
        {draft.kind === 'edit' && <p className="hint">Saving replaces the old version. It stays in the history, crossed out, with your name.</p>}

        <label className="amount-field">
          <span className="visually-hidden">Amount in euros</span>
          <input value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="decimal" placeholder="0,00"
            autoFocus={draft.kind === 'new'} aria-invalid={amount !== '' && total === null} />
          <span className="cur">€</span>
        </label>

        <Field label="What for?">
          <input value={description} onChange={(e) => setDescription(e.target.value)} required maxLength={200}
            placeholder="e.g. REWE, Pizza, Bahn tickets" />
        </Field>

        <div className="field">
          <span className="label">Category{chosenCategory === undefined && category ? ' (suggested)' : ''}</span>
          <div className="chip-row" role="group" aria-label="Category">
            {CATEGORIES.map((c) => (
              <button type="button" key={c.id} className="chip" aria-pressed={category === c.id}
                onClick={() => setChosenCategory(category === c.id ? null : c.id)}>
                <span aria-hidden="true">{c.emoji}</span>{c.label}
              </button>
            ))}
          </div>
        </div>

        <div className="field">
          <span className="label">Paid by</span>
          <div className="chip-row" role="group" aria-label="Paid by">
            {members.map((m) => (
              <button type="button" key={m.id} aria-pressed={payerId === m.id}
                className="chip person-chip" onClick={() => setPayerId(m.id)}>
                <Avatar id={m.id} name={m.displayName} />
                <span>{m.id === myMemberId ? 'You' : m.displayName}</span>
              </button>
            ))}
          </div>
        </div>

        {mode === 'EQUAL' && (
          <div className="field">
            <span className="label">Split equally between</span>
            <div className="chip-row" role="group" aria-label="Split between">
              {members.map((m) => {
                const included = !excluded.includes(m.id);
                return (
                  <button type="button" key={m.id} className="chip person-chip" aria-pressed={included}
                    aria-label={`${m.displayName} shares`}
                    onClick={() => setExcluded(included ? [...excluded, m.id] : excluded.filter((id) => id !== m.id))}>
                    <Avatar id={m.id} name={m.displayName} />
                    <span>{m.id === myMemberId ? 'You' : m.displayName}</span>
                    <span className="share">{shareOf(m.id) > 0 ? formatCents(shareOf(m.id)) : '–'}</span>
                  </button>
                );
              })}
            </div>
          </div>
        )}

        <details className="more" open={initial.mode !== 'EQUAL'}>
          <summary>{mode === 'EQUAL' ? 'Split unevenly' : 'How it is split'}</summary>
          <div className="stack" style={{ marginTop: 12 }}>
            <div className="segmented" role="group" aria-label="How is it split?">
              {SPLIT_MODES.map((m) => (
                <button type="button" key={m.id} aria-pressed={mode === m.id} onClick={() => setMode(m.id)}>{m.label}</button>
              ))}
            </div>
            <p className="hint">{SPLIT_MODES.find((m) => m.id === mode)!.hint}</p>
            {mode !== 'EQUAL' && (
              <ul className="list split-rows">
                {members.map((m) => (
                  <li key={m.id} className="row">
                    <Avatar id={m.id} name={m.displayName} size="small" />
                    <span className="grow"><span className="title">{m.displayName}{m.id === myMemberId ? ' (you)' : ''}</span></span>
                    {mode === 'EXACT' ? (
                      <input inputMode="decimal" placeholder="0,00" aria-label={`Amount for ${m.displayName}`}
                        value={exact[m.id] ?? ''} aria-invalid={exactCents.get(m.id) === null}
                        onChange={(e) => setExact({ ...exact, [m.id]: e.target.value })} />
                    ) : (
                      <>
                        <input inputMode="numeric" aria-label={`Shares of ${m.displayName}`} style={{ width: 70 }}
                          value={shares[m.id] ?? '1'} aria-invalid={Number.isNaN(shareUnits.get(m.id) ?? 0)}
                          onChange={(e) => setShares({ ...shares, [m.id]: e.target.value })} />
                        <span className="num muted small" style={{ minWidth: 72, textAlign: 'right' }}>
                          {shareOf(m.id) > 0 ? formatCents(shareOf(m.id)) : '–'}
                        </span>
                      </>
                    )}
                  </li>
                ))}
              </ul>
            )}
            {mode === 'EXACT' && total !== null && !invalidExact && (
              <div className={`assigned ${remaining === 0 ? 'done' : remaining < 0 ? 'over' : ''}`}>
                <span>{formatCents(assigned)} of {formatCents(total)} assigned</span>
                {remaining > 0 && <button type="button" className="btn btn-quiet btn-small" onClick={splitRest}>Split the rest</button>}
              </div>
            )}
          </div>
        </details>

        <Field label="Date">
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)} required />
        </Field>

        {problem && amount !== '' && <p className="hint" role="status">{problem}</p>}
        <button className="btn btn-primary btn-block" disabled={problem !== null || busy}>
          {draft.kind === 'edit' ? 'Save changes'
            : <>{draft.kind === 'bank' ? 'Share' : 'Add'}&nbsp;{total !== null ? <Money cents={total} /> : 'expense'}</>}
        </button>
      </form>
    </Sheet>
  );
}
